import type { FastifyBaseLogger, FastifyInstance } from "fastify";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { formatUnits, parseUnits, type Address } from "viem";
import { db, type MerchantRow, type WithdrawalRow } from "../db/client.js";
import { badRequest, conflict, forbidden, internal, notFound, unavailable } from "../lib/errors.js";
import { emitWithdrawalEvent } from "../lib/events.js";
import { getSigner, subjectForMerchant } from "../chain/signer.js";
import { USDC_DECIMALS, getUsdcBalance } from "../chain/usdc.js";
import { classifyFailure } from "../chain/failures.js";
import { unlinkEnabled, privateWithdraw } from "../chain/unlink.js";
import { requireAuth } from "../auth/middleware.js";
import { registerMerchantEns } from "../ens/registrar.js";

const uuid = z.string().uuid();
const evmAddress = z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida");

const createMerchantSchema = z.object({
  name: z.string().min(1),
  // Opcional: si no se envía, el backend deriva la smart account (ERC-4337) del comerciante.
  smartAccountAddress: evmAddress.optional(),
  // Opcional: subdominio ENS a usar; si no, se usa el nombre slugificado.
  ensLabel: z.string().min(1).optional(),
});

const updateMerchantSchema = z.object({
  name: z.string().min(1),
});

const withdrawSchema = z.object({
  to: evmAddress,
  amount: z.number().positive(),
  // true → liquidado en privado por el pool de Unlink (rompe el link comercio→destino).
  private: z.boolean().optional().default(false),
});

// Crea un comercio derivando su smart account si no trae una externa. Si trae dirección externa →
// custodial=false (no retira: el backend no tiene su llave).
export async function createMerchant(input: {
  name: string;
  ownerUserId: string;
  smartAccountAddress?: string;
  ensLabel?: string;
  log: FastifyBaseLogger;
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
    .insert({
      id,
      name: input.name,
      smart_account_address: smartAccountAddress,
      custodial,
      owner_user_id: input.ownerUserId,
    })
    .select()
    .single<MerchantRow>();
  if (error || !data) throw internal("no se pudo crear el comercio");

  void registerMerchantEns(data, input.log, input.ensLabel);

  return data;
}

const serializeMerchant = (m: MerchantRow) => ({
  id: m.id,
  name: m.name,
  smartAccountAddress: m.smart_account_address,
  custodial: m.custodial,
  ensName: m.ens_name,
});

// Carga un comercio activo y exige que sea del usuario autenticado. 404 si no existe, 403 si es ajeno.
export async function loadOwnedMerchant(userId: string, merchantId: string): Promise<MerchantRow> {
  if (!uuid.safeParse(merchantId).success) throw badRequest("id de comercio inválido", "validation_error");

  const { data } = await db
    .from("merchants")
    .select("*")
    .eq("id", merchantId)
    .is("deleted_at", null)
    .maybeSingle<MerchantRow>();
  if (!data) throw notFound("comercio no encontrado", "merchant_not_found");
  if (data.owner_user_id !== userId) {
    throw forbidden("no eres el dueño de este comercio", "not_account_owner");
  }
  return data;
}

export async function merchantRoutes(app: FastifyInstance): Promise<void> {
  app.post("/merchants", { preHandler: requireAuth }, async (request, reply) => {
    const parsed = createMerchantSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const merchant = await createMerchant({
      ...parsed.data,
      ownerUserId: request.userId!,
      log: request.log,
    });
    return reply.code(201).send(serializeMerchant(merchant));
  });

  app.get("/merchants", { preHandler: requireAuth }, async (request, reply) => {
    const { data } = await db
      .from("merchants")
      .select("*")
      .eq("owner_user_id", request.userId)
      .is("deleted_at", null);
    const merchants = data ?? [];
    // Backfill: merchants created before ENS (or whose registration failed) get a subname too.
    for (const m of merchants) {
      if (!m.ens_name) void registerMerchantEns(m, request.log);
    }
    return reply.code(200).send(merchants.map(serializeMerchant));
  });

  app.get<{ Params: { id: string } }>(
    "/merchants/:id",
    { preHandler: requireAuth },
    async (request, reply) => {
      const merchant = await loadOwnedMerchant(request.userId!, request.params.id);
      if (!merchant.ens_name) void registerMerchantEns(merchant, request.log);
      const balance = await getUsdcBalance(merchant.smart_account_address as Address);
      return reply.code(200).send({
        ...serializeMerchant(merchant),
        usdcBalance: formatUnits(balance, USDC_DECIMALS),
      });
    },
  );

  app.patch<{ Params: { id: string } }>(
    "/merchants/:id",
    { preHandler: requireAuth },
    async (request, reply) => {
      const merchant = await loadOwnedMerchant(request.userId!, request.params.id);
      const parsed = updateMerchantSchema.safeParse(request.body);
      if (!parsed.success) {
        throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
      }

      const { data, error } = await db
        .from("merchants")
        .update({ name: parsed.data.name })
        .eq("id", merchant.id)
        .select()
        .single<MerchantRow>();
      if (error || !data) throw internal("no se pudo actualizar el comercio");
      return reply.code(200).send(serializeMerchant(data));
    },
  );

  // Soft-delete (preserva el historial de pagos/retiros). Bloquea si la cuenta custodial tiene saldo.
  app.delete<{ Params: { id: string } }>(
    "/merchants/:id",
    { preHandler: requireAuth },
    async (request, reply) => {
      const merchant = await loadOwnedMerchant(request.userId!, request.params.id);

      if (merchant.custodial) {
        const balance = await getUsdcBalance(merchant.smart_account_address as Address);
        if (balance > 0n) {
          throw conflict(
            "el comercio tiene saldo USDC; retíralo antes de eliminarlo",
            "must_withdraw_first",
          );
        }
      }

      await db.from("merchants").update({ deleted_at: new Date().toISOString() }).eq("id", merchant.id);
      return reply.code(204).send();
    },
  );

  app.post<{ Params: { id: string } }>(
    "/merchants/:id/withdraw",
    { preHandler: requireAuth },
    async (request, reply) => {
      const merchant = await loadOwnedMerchant(request.userId!, request.params.id);

      const parsed = withdrawSchema.safeParse(request.body);
      if (!parsed.success) {
        throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
      }
      const { to, amount, private: isPrivate } = parsed.data;

      // Solo cuentas derivadas por el backend pueden retirar: de una dirección externa (wallet/
      // exchange del comercio) el backend no tiene la llave para firmar.
      if (!merchant.custodial) {
        throw conflict(
          "la cuenta del comercio es externa (no custodial): el backend no controla su llave y no puede retirar",
          "account_not_custodial",
        );
      }
      if (isPrivate && !unlinkEnabled()) {
        throw unavailable(
          "el retiro privado no está configurado (UNLINK_API_KEY)",
          "unlink_not_configured",
        );
      }

      const { data: withdrawal, error } = await db
        .from("withdrawals")
        .insert({ merchant_id: merchant.id, to_address: to, amount, status: "pending", is_private: isPrivate })
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
    { preHandler: requireAuth },
    async (request, reply) => {
      if (!uuid.safeParse(request.params.id).success) {
        throw badRequest("id de retiro inválido", "validation_error");
      }

      const { data } = await db
        .from("withdrawals")
        .select("*")
        .eq("id", request.params.id)
        .maybeSingle<WithdrawalRow>();
      if (!data) throw notFound("retiro no encontrado", "withdrawal_not_found");

      // El retiro debe pertenecer a un comercio del usuario autenticado; si no → 404 (no filtrar ajenos).
      const { data: merchant } = await db
        .from("merchants")
        .select("owner_user_id")
        .eq("id", data.merchant_id)
        .maybeSingle<Pick<MerchantRow, "owner_user_id">>();
      if (!merchant || merchant.owner_user_id !== request.userId) {
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
        isPrivate: data.is_private,
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
    // Retiro privado: vía pool de Unlink (deposit con USDC del comercio → withdraw privado).
    if (withdrawal.is_private) {
      const { txHash } = await privateWithdraw({
        merchantId: withdrawal.merchant_id,
        to: withdrawal.to_address,
        amount: withdrawal.amount,
      });
      await db.from("withdrawals").update({ status: "settled", tx_hash: txHash }).eq("id", withdrawalId);
      emitWithdrawalEvent({ type: "settled", withdrawalId, txHash });
      return;
    }

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
