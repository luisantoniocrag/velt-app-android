import type { FastifyBaseLogger, FastifyInstance } from "fastify";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { parseUnits, type Address } from "viem";
import { db, type MerchantRow, type WithdrawalRow } from "../db/client.js";
import { badRequest, conflict, forbidden, internal, notFound } from "../lib/errors.js";
import { emitWithdrawalEvent } from "../lib/events.js";
import { getSigner, subjectForMerchant } from "../chain/signer.js";
import { USDC_DECIMALS } from "../chain/usdc.js";
import { classifyFailure } from "../chain/failures.js";
import { requireMerchantAuth } from "../auth/middleware.js";

const uuid = z.string().uuid();
const evmAddress = z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida");

const createMerchantSchema = z.object({
  name: z.string().min(1),
  // Opcional: si no se envía, el backend deriva la smart account (ERC-4337) del comerciante.
  smartAccountAddress: evmAddress.optional(),
});

const withdrawSchema = z.object({
  to: evmAddress,
  amount: z.number().positive(),
});

// Crea un comerciante derivando su smart account si no trae una externa. Reusado por POST /merchants
// y por el self-signup de auth (routes/auth.ts). Si trae dirección externa → custodial=false (no
// retira: el backend no tiene su llave).
export async function createMerchant(input: {
  name: string;
  smartAccountAddress?: string;
}): Promise<MerchantRow> {
  // El id se genera aquí para poder derivar la cuenta antes del insert (smart_account_address es not null).
  const id = randomUUID();
  const custodial = !input.smartAccountAddress;
  let smartAccountAddress = input.smartAccountAddress;
  if (!smartAccountAddress) {
    const signer = await getSigner();
    const { address } = await signer.getOrCreateAccount(subjectForMerchant(id));
    smartAccountAddress = address;
  }

  const { data, error } = await db
    .from("merchants")
    .insert({ id, name: input.name, smart_account_address: smartAccountAddress, custodial })
    .select()
    .single<MerchantRow>();
  if (error || !data) throw internal("no se pudo crear el comerciante");
  return data;
}

export async function merchantRoutes(app: FastifyInstance): Promise<void> {
  // TODO v2: abierto (creación programática / E2E de pagos). El self-signup con identidad va por
  // POST /api/v1/auth/register. Proteger o unificar ambos.
  app.post("/merchants", async (request, reply) => {
    const parsed = createMerchantSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const merchant = await createMerchant(parsed.data);
    return reply.code(201).send({
      id: merchant.id,
      name: merchant.name,
      smartAccountAddress: merchant.smart_account_address,
      custodial: merchant.custodial,
    });
  });

  app.post<{ Params: { id: string } }>(
    "/merchants/:id/withdraw",
    { preHandler: requireMerchantAuth },
    async (request, reply) => {
      const idCheck = uuid.safeParse(request.params.id);
      if (!idCheck.success) throw badRequest("id de comerciante inválido", "validation_error");

      // Solo el propio comerciante autenticado puede retirar de su cuenta.
      if (request.merchantId !== request.params.id) {
        throw forbidden("no puedes retirar de otra cuenta", "not_account_owner");
      }

      const parsed = withdrawSchema.safeParse(request.body);
      if (!parsed.success) {
        throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
      }
      const { to, amount } = parsed.data;

      const { data: merchant } = await db
        .from("merchants")
        .select("id, custodial")
        .eq("id", request.params.id)
        .maybeSingle<Pick<MerchantRow, "id" | "custodial">>();
      if (!merchant) throw notFound("comerciante no encontrado", "merchant_not_found");

      // Solo cuentas derivadas por el backend pueden retirar: de una dirección externa (wallet/
      // exchange del comerciante) el backend no tiene la llave para firmar.
      if (!merchant.custodial) {
        throw conflict(
          "la cuenta del comerciante es externa (no custodial): el backend no controla su llave y no puede retirar",
          "account_not_custodial",
        );
      }

      const { data: withdrawal, error } = await db
        .from("withdrawals")
        .insert({ merchant_id: merchant.id, to_address: to, amount, status: "pending" })
        .select()
        .single<WithdrawalRow>();
      if (error || !withdrawal) {
        request.log.error({ err: error }, "fallo al crear withdrawal");
        throw internal("no se pudo iniciar el retiro");
      }

      // El resultado real (settled/failed) llega por WebSocket: la confirmación on-chain es
      // asíncrona. Respondemos 202 y seguimos en segundo plano.
      reply.code(202).send({
        withdrawalId: withdrawal.id,
        status: withdrawal.status,
        to,
        amount,
        wsUrl: `/ws/withdrawals/${withdrawal.id}`,
      });

      void processWithdrawal({ log: request.log, withdrawal });

      return reply;
    },
  );

  app.get<{ Params: { id: string } }>(
    "/withdrawals/:id",
    { preHandler: requireMerchantAuth },
    async (request, reply) => {
      const idCheck = uuid.safeParse(request.params.id);
      if (!idCheck.success) throw badRequest("id de retiro inválido", "validation_error");

      const { data } = await db
        .from("withdrawals")
        .select("*")
        .eq("id", request.params.id)
        .maybeSingle<WithdrawalRow>();
      if (!data) throw notFound("retiro no encontrado", "withdrawal_not_found");

      // No filtrar existencia de retiros ajenos: si no es del comerciante autenticado → 404.
      if (data.merchant_id !== request.merchantId) {
        throw notFound("retiro no encontrado", "withdrawal_not_found");
      }

      return reply.code(200).send({
        withdrawalId: data.id,
        merchantId: data.merchant_id,
        to: data.to_address,
        amount: Number(data.amount),
        status: data.status,
        txHash: data.tx_hash,
        reason: data.reason,
      });
    },
  );
}

// Parte asíncrona del retiro. Nunca lanza: cualquier fallo → status=failed + evento WS.
async function processWithdrawal(args: {
  log: FastifyBaseLogger;
  withdrawal: WithdrawalRow;
}): Promise<void> {
  const { log, withdrawal } = args;
  const withdrawalId = withdrawal.id;

  await db.from("withdrawals").update({ status: "processing" }).eq("id", withdrawalId);
  emitWithdrawalEvent({ type: "processing", withdrawalId });

  try {
    const signer = await getSigner();

    // Rehidrata el mapa address→subject que necesita signAndSendUserOp (firmar en frío fallaría).
    const { address: from } = await signer.getOrCreateAccount(subjectForMerchant(withdrawal.merchant_id));

    // parseUnits(String(...)): PostgREST devuelve numeric como string.
    const amountUsdc = parseUnits(String(withdrawal.amount), USDC_DECIMALS);
    const { txHash } = await signer.signAndSendUserOp({
      from,
      to: withdrawal.to_address as Address,
      amountUsdc,
    });

    await db
      .from("withdrawals")
      .update({ status: "settled", tx_hash: txHash })
      .eq("id", withdrawalId);
    emitWithdrawalEvent({ type: "settled", withdrawalId, txHash });
  } catch (err) {
    const reason = classifyFailure(err);
    log.error({ err, withdrawalId }, `retiro fallido: ${reason}`);
    await db.from("withdrawals").update({ status: "failed", reason }).eq("id", withdrawalId);
    emitWithdrawalEvent({ type: "failed", withdrawalId, reason });
  }
}
