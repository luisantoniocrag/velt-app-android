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
import { classifyFailure } from "../chain/failures.js";

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
  app.post("/payments/initiate", async (request, reply) => {
    const parsed = initiateSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    const { merchantId, amount } = parsed.data;

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

// Parte asíncrona del pago. Nunca lanza: cualquier fallo → status=failed + evento WS.
async function settlePayment(args: {
  log: FastifyBaseLogger;
  payment: PaymentRequestRow;
  personId: string;
}): Promise<void> {
  const { log, payment, personId } = args;
  const paymentId = payment.id;

  emitPaymentEvent({ type: "authorizing", paymentId });

  try {
    const signer = await getSigner();

    // Idempotente: deja caliente el mapa address→personId que necesita signAndSendUserOp.
    const { address: payerAddress } = await signer.getOrCreateAccount(personId);

    const user = await ensureVeltUser(personId, payerAddress, log);
    await db
      .from("payment_requests")
      .update({ payer_user_id: user.id })
      .eq("id", paymentId);

    const { data: merchant } = await db
      .from("merchants")
      .select("smart_account_address")
      .eq("id", payment.merchant_id)
      .maybeSingle<Pick<MerchantRow, "smart_account_address">>();
    if (!merchant) throw new Error("merchant desapareció durante la liquidación");

    // parseUnits(String(...)): PostgREST devuelve numeric como string.
    const amountUsdc = parseUnits(String(payment.amount), USDC_DECIMALS);
    const { txHash } = await signer.signAndSendUserOp({
      from: payerAddress,
      to: merchant.smart_account_address as Address,
      amountUsdc,
    });

    await db
      .from("payment_requests")
      .update({ status: "settled", tx_hash: txHash })
      .eq("id", paymentId);
    emitPaymentEvent({ type: "settled", paymentId, txHash, payerPersonId: personId });
  } catch (err) {
    const reason = classifyFailure(err);
    log.error({ err, paymentId }, `pago fallido: ${reason}`);
    await db.from("payment_requests").update({ status: "failed" }).eq("id", paymentId);
    emitPaymentEvent({ type: "failed", paymentId, reason });
  }
}

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
