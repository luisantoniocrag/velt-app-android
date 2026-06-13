import type { FastifyInstance } from "fastify";
import { formatUnits, type Address } from "viem";
import {
  db,
  type MerchantRow,
  type PaymentRequestRow,
  type VeltUserRow,
} from "../db/client.js";
import { badRequest, notFound } from "../lib/errors.js";
import { getUsdcBalance, USDC_DECIMALS } from "../chain/usdc.js";

// Scan-to-balance: el pagador escanea su palma → la app resuelve el personId (bioserver) y
// consulta aquí su wallet (saldo USDC on-chain + ENS + historial). Sin auth: el pagador no
// tiene cuenta; su palma ES la credencial, y solo se muestra si ya tiene wallet asociada.
export async function payerRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { personId: string } }>("/payers/:personId/wallet", async (request, reply) => {
    const personId = request.params.personId;
    if (!personId) throw badRequest("personId requerido", "validation_error");

    const { data: user } = await db
      .from("velt_users")
      .select("*")
      .eq("person_id", personId)
      .maybeSingle<VeltUserRow>();
    if (!user) throw notFound("no hay wallet asociada a esta palma", "wallet_not_found");

    const balance = await getUsdcBalance(user.smart_account_address as Address);

    const { data: payments } = await db
      .from("payment_requests")
      .select("id, amount, status, tx_hash, created_at, merchant_id")
      .eq("payer_person_id", personId)
      .order("created_at", { ascending: false })
      .limit(20);
    const rows = (payments ?? []) as Array<
      Pick<PaymentRequestRow, "id" | "amount" | "status" | "tx_hash" | "created_at" | "merchant_id">
    >;

    const merchantIds = [...new Set(rows.map((p) => p.merchant_id))];
    const { data: merchants } = merchantIds.length
      ? await db.from("merchants").select("id, name").in("id", merchantIds)
      : { data: [] as Pick<MerchantRow, "id" | "name">[] };
    const nameById = new Map((merchants ?? []).map((m) => [m.id, m.name]));

    return reply.code(200).send({
      personId,
      address: user.smart_account_address,
      ensName: user.ens_name,
      usdcBalance: formatUnits(balance, USDC_DECIMALS),
      transactions: rows.map((p) => ({
        paymentId: p.id,
        amount: Number(p.amount),
        status: p.status,
        txHash: p.tx_hash,
        merchant: nameById.get(p.merchant_id) ?? null,
        createdAt: p.created_at,
      })),
    });
  });
}
