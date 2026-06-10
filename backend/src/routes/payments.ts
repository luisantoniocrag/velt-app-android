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
import { config } from "../config.js";
import { getSigner, OPERATOR_SUBJECT } from "../chain/signer.js";
import { USDC_ADDRESS, USDC_DECIMALS, encodeUsdcApprove } from "../chain/usdc.js";
import { escrowAddress, encodeEscrowHold, encodeEscrowRelease } from "../chain/escrow.js";
import { classifyFailure } from "../chain/failures.js";
import { requireAuth } from "../auth/middleware.js";
import { registerPayerEns } from "../ens/registrar.js";
import { loadOwnedMerchant } from "./merchants.js";

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
      .is("deleted_at", null)
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

    // El resultado real (held → settled/failed) llega por WebSocket: la confirmación on-chain
    // es asíncrona. Respondemos 202 y seguimos en segundo plano.
    reply.code(202).send({ paymentId, status: "authorizing" });

    void settlePayment({
      log: request.log,
      payment: claimed,
      personId,
    });

    return reply;
  });

  // The merchant owner confirms delivery → the operator releases the escrow to the merchant.
  app.post<{ Params: { id: string } }>(
    "/payments/:id/confirm",
    { preHandler: requireAuth },
    async (request, reply) => {
      const idCheck = uuid.safeParse(request.params.id);
      if (!idCheck.success) throw badRequest("id de pago inválido", "validation_error");

      const { data: payment } = await db
        .from("payment_requests")
        .select("*")
        .eq("id", request.params.id)
        .maybeSingle<PaymentRequestRow>();
      if (!payment) throw notFound("pago no encontrado", "payment_not_found");

      await loadOwnedMerchant(request.userId!, payment.merchant_id);

      if (payment.status !== "held") {
        throw conflict(`el pago no está en estado held (actual: ${payment.status})`, "invalid_state");
      }

      // The outcome (settled + txHash) arrives via WebSocket or GET /payments/:id.
      reply.code(202).send({ paymentId: payment.id, status: payment.status });

      void releasePayment({ log: request.log, payment });

      return reply;
    },
  );

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
      escrowTxHash: data.escrow_tx_hash,
      releaseTxHash: data.release_tx_hash,
      releaseAfter: data.release_after,
      payerPersonId: data.payer_person_id,
      payerEnsName: await payerEnsNameFor(data.payer_user_id),
    });
  });
}

// Async part of the payment. Never throws: any failure → status=failed + WS event.
// Settlement = escrow hold (approve + hold batched in one payer UserOp); settled comes
// later via merchant /confirm or auto-release.
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

    // Idempotente: deja caliente el mapa address→personId que necesita signAndSendCalls.
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
    const escrow = escrowAddress();
    const { txHash } = await signer.signAndSendCalls({
      from: payerAddress,
      calls: [
        { to: USDC_ADDRESS, data: encodeUsdcApprove(escrow, amountUsdc) },
        {
          to: escrow,
          data: encodeEscrowHold(paymentId, merchant.smart_account_address as Address, amountUsdc),
        },
      ],
    });

    // DECISION: backend clock approximates the contract's releaseAfter (block.timestamp at
    // hold time); the 60s auto-release cadence absorbs the drift.
    const releaseAfter = new Date(
      Date.now() + config.ESCROW_RELEASE_DELAY_SECONDS * 1000,
    ).toISOString();

    await db
      .from("payment_requests")
      .update({ status: "held", escrow_tx_hash: txHash, release_after: releaseAfter })
      .eq("id", paymentId);
    emitPaymentEvent({ type: "held", paymentId, escrowTxHash: txHash, releaseAfter });
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

  void registerPayerEns(data, log);

  return data;
}

async function payerEnsNameFor(payerUserId: string | null): Promise<string | null> {
  if (!payerUserId) return null;
  const { data } = await db
    .from("velt_users")
    .select("ens_name")
    .eq("id", payerUserId)
    .maybeSingle<Pick<VeltUserRow, "ens_name">>();
  return data?.ens_name ?? null;
}

// In-process guard: /confirm and the auto-release sweep can race over the same payment.
// Single-instance is the v1 norm; the contract reverts a true double release anyway.
const releasing = new Set<string>();

// Releases the escrow as operator and marks settled. Never throws: if the release fails the
// payment stays held (funds remain in escrow) and the next sweep retries it.
async function releasePayment(args: {
  log: FastifyBaseLogger;
  payment: PaymentRequestRow;
}): Promise<void> {
  const { log, payment } = args;
  const paymentId = payment.id;

  if (releasing.has(paymentId)) return;
  releasing.add(paymentId);

  try {
    const signer = await getSigner();

    // Idempotent: warms the address→subject map that signAndSendCalls needs.
    const { address: operatorAddress } = await signer.getOrCreateAccount(OPERATOR_SUBJECT);

    const { txHash } = await signer.signAndSendCalls({
      from: operatorAddress,
      calls: [{ to: escrowAddress(), data: encodeEscrowRelease(paymentId) }],
    });

    await db
      .from("payment_requests")
      .update({ status: "settled", tx_hash: txHash, release_tx_hash: txHash })
      .eq("id", paymentId);
    emitPaymentEvent({
      type: "settled",
      paymentId,
      txHash,
      payerPersonId: payment.payer_person_id ?? "",
      payerEnsName: await payerEnsNameFor(payment.payer_user_id),
    });
  } catch (err) {
    log.error({ err, paymentId }, `release fallido: ${classifyFailure(err)}`);
  } finally {
    releasing.delete(paymentId);
  }
}

// Automatic release: held payments past release_after get released without merchant
// confirmation. Single-instance, same as the socket registry.
export function startEscrowAutoRelease(log: FastifyBaseLogger): NodeJS.Timeout {
  return setInterval(async () => {
    const { data } = await db
      .from("payment_requests")
      .select("*")
      .eq("status", "held")
      .lte("release_after", new Date().toISOString());

    for (const payment of data ?? []) {
      void releasePayment({ log, payment });
    }
  }, 60_000);
}
