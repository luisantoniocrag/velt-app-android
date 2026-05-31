import type { FastifyBaseLogger, FastifyInstance } from "fastify";
import { z } from "zod";
import { parseUnits, type Address } from "viem";
import {
  db,
  type MerchantRow,
  type PaymentRequestRow,
  type VeltUserRow,
} from "../db/client.js";
import { badRequest, conflict, internal, notFound } from "../lib/errors.js";
import { emitPaymentEvent } from "../lib/events.js";
import { getSigner } from "../chain/signer.js";
import { USDC_DECIMALS } from "../chain/usdc.js";

const uuid = z.string().uuid();

const initiateSchema = z.object({
  merchantId: uuid,
  amount: z.number().positive(),
});

const authorizeSchema = z.object({
  paymentId: uuid,
  personId: z.string().min(1, "personId no puede estar vacío"),
});

export async function paymentRoutes(app: FastifyInstance): Promise<void> {
  // 6.2 POST /api/v1/payments/initiate ───────────────────────────────
  app.post("/payments/initiate", async (request, reply) => {
    const parsed = initiateSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    const { merchantId, amount } = parsed.data;

    // merchantId debe existir.
    const { data: merchant } = await db
      .from("merchants")
      .select("id")
      .eq("id", merchantId)
      .maybeSingle<Pick<MerchantRow, "id">>();
    if (!merchant) throw notFound("comerciante no encontrado", "merchant_not_found");

    const { data, error } = await db
      .from("payment_requests")
      .insert({ merchant_id: merchantId, amount, status: "pending" })
      .select()
      .single<PaymentRequestRow>();
    if (error || !data) {
      request.log.error({ err: error }, "fallo al crear payment_request");
      throw internal("no se pudo iniciar el cobro");
    }

    return reply.code(201).send({
      paymentId: data.id,
      status: data.status,
      amount: Number(data.amount),
      wsUrl: `/ws/payments/${data.id}`,
    });
  });

  // 6.3 POST /api/v1/payments/authorize ──────────────────────────────
  app.post("/payments/authorize", async (request, reply) => {
    const parsed = authorizeSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    const { paymentId, personId } = parsed.data;

    const { data: payment } = await db
      .from("payment_requests")
      .select("*")
      .eq("id", paymentId)
      .maybeSingle<PaymentRequestRow>();
    if (!payment) throw notFound("pago no encontrado", "payment_not_found");
    if (payment.status !== "pending") {
      throw conflict(`el pago no está en estado pending (actual: ${payment.status})`, "invalid_state");
    }

    // Claim atómico: solo un authorize gana la carrera pending → authorizing.
    const { data: claimed } = await db
      .from("payment_requests")
      .update({ status: "authorizing", payer_person_id: personId })
      .eq("id", paymentId)
      .eq("status", "pending")
      .select()
      .maybeSingle<PaymentRequestRow>();
    if (!claimed) {
      throw conflict("el pago ya está siendo procesado", "invalid_state");
    }

    // El resultado real (settled/failed) llega por WebSocket: la confirmación on-chain
    // es asíncrona. Respondemos 202 y seguimos en segundo plano.
    reply.code(202).send({ paymentId, status: "authorizing" });

    void settlePayment({
      log: request.log,
      payment: claimed,
      personId,
    });

    return reply;
  });

  // 6.4 GET /api/v1/payments/:id ─────────────────────────────────────
  app.get<{ Params: { id: string } }>("/payments/:id", async (request, reply) => {
    const idCheck = uuid.safeParse(request.params.id);
    if (!idCheck.success) throw badRequest("id de pago inválido", "validation_error");

    const { data } = await db
      .from("payment_requests")
      .select("*")
      .eq("id", request.params.id)
      .maybeSingle<PaymentRequestRow>();
    if (!data) throw notFound("pago no encontrado", "payment_not_found");

    return reply.code(200).send({
      paymentId: data.id,
      status: data.status,
      amount: Number(data.amount),
      txHash: data.tx_hash,
      payerPersonId: data.payer_person_id,
    });
  });
}

/**
 * Orquesta la parte asíncrona del pago: crea la cuenta del pagador si hace falta,
 * ejecuta la transferencia USDC on-chain y emite los eventos WS correspondientes.
 * Nunca lanza: cualquier fallo se traduce a status=failed + evento WS.
 */
async function settlePayment(args: {
  log: FastifyBaseLogger;
  payment: PaymentRequestRow;
  personId: string;
}): Promise<void> {
  const { log, payment, personId } = args;
  const paymentId = payment.id;

  // Avisar de inmediato que estamos autorizando.
  emitPaymentEvent({ type: "authorizing", paymentId });

  try {
    const signer = await getSigner();

    // 1. Smart account del pagador (idempotente; crea/deriva la primera vez).
    const { address: payerAddress } = await signer.getOrCreateAccount(personId);

    // 2. Asegurar el registro velt_users (crear si es nuevo personId).
    const user = await ensureVeltUser(personId, payerAddress, log);
    await db
      .from("payment_requests")
      .update({ payer_user_id: user.id })
      .eq("id", paymentId);

    // 3. Resolver destino = smart account del comerciante.
    const { data: merchant } = await db
      .from("merchants")
      .select("smart_account_address")
      .eq("id", payment.merchant_id)
      .maybeSingle<Pick<MerchantRow, "smart_account_address">>();
    if (!merchant) throw new Error("merchant desapareció durante la liquidación");

    // 4. Transferir USDC (espera recibo on-chain).
    const amountUsdc = parseUnits(String(payment.amount), USDC_DECIMALS);
    const { txHash } = await signer.signAndSendUserOp({
      from: payerAddress,
      to: merchant.smart_account_address as Address,
      amountUsdc,
    });

    // 5. Liquidado.
    await db
      .from("payment_requests")
      .update({ status: "settled", tx_hash: txHash })
      .eq("id", paymentId);
    emitPaymentEvent({ type: "settled", paymentId, txHash, payerPersonId: personId });
  } catch (err) {
    const reason = classifyFailure(err);
    // El motivo detallado solo va al log; al cliente, un código genérico.
    log.error({ err, paymentId }, `pago fallido: ${reason}`);
    await db.from("payment_requests").update({ status: "failed" }).eq("id", paymentId);
    emitPaymentEvent({ type: "failed", paymentId, reason });
  }
}

/** Busca o crea el velt_user para un personId. */
async function ensureVeltUser(
  personId: string,
  smartAccountAddress: string,
  log: FastifyBaseLogger,
): Promise<VeltUserRow> {
  const { data: existing } = await db
    .from("velt_users")
    .select("*")
    .eq("person_id", personId)
    .maybeSingle<VeltUserRow>();
  if (existing) return existing;

  const { data, error } = await db
    .from("velt_users")
    .insert({ person_id: personId, smart_account_address: smartAccountAddress })
    .select()
    .single<VeltUserRow>();
  if (error || !data) {
    // Carrera: otro authorize del mismo personId pudo insertarlo primero.
    const { data: retry } = await db
      .from("velt_users")
      .select("*")
      .eq("person_id", personId)
      .maybeSingle<VeltUserRow>();
    if (retry) return retry;
    log.error({ err: error }, "fallo al crear velt_user");
    throw new Error("no se pudo crear la cuenta del usuario");
  }
  return data;
}

/** Traduce un error de la capa on-chain a un motivo genérico para el cliente. */
function classifyFailure(err: unknown): string {
  const msg = (err instanceof Error ? err.message : String(err)).toLowerCase();
  if (msg.includes("insufficient") || msg.includes("balance") || msg.includes("funds")) {
    return "insufficient_funds";
  }
  if (msg.includes("timeout") || msg.includes("timed out")) return "rpc_timeout";
  if (msg.includes("revert")) return "tx_reverted";
  return "payment_failed";
}
