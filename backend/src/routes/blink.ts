import { createSign, randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { config } from "../config.js";
import { db, type DepositRow } from "../db/client.js";
import { badRequest, internal, unavailable } from "../lib/errors.js";
import { getSigner } from "../chain/signer.js";

// Funding destination decided for the demo: Blink does not deposit on Arc, so payer funds
// land on the payer's smart account on Base (same deterministic derivation, EVM address is
// chain-agnostic). No bridge to Arc in this phase.
export const BLINK_DEST_CHAIN_ID = 8453;
export const BLINK_DEST_TOKEN = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913";

const ALLOWED_CALLBACK_SCHEMES = new Set(["velt"]);

export const blinkEnabled = (): boolean =>
  Boolean(config.BLINK_MERCHANT_ID && config.BLINK_MERCHANT_PRIVATE_KEY);

export function requireBlink(): void {
  if (!blinkEnabled()) {
    throw unavailable(
      "Blink no está configurado (BLINK_MERCHANT_ID / BLINK_MERCHANT_PRIVATE_KEY)",
      "blink_not_configured",
    );
  }
}

// PEM stored in .env with literal \n line breaks.
const merchantPrivateKeyPem = (): string =>
  config.BLINK_MERCHANT_PRIVATE_KEY!.replace(/\\n/g, "\n");

const evmAddress = z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida");

const signerRequestSchema = z.object({
  amount: z.number().finite().positive(),
  chainId: z.number().int().positive(),
  address: evmAddress,
  token: evmAddress,
  callbackScheme: z
    .string()
    .regex(/^[a-zA-Z][a-zA-Z0-9+\-.]*$/, "callbackScheme inválido")
    .nullable()
    .optional()
    .default(null),
  url: z.string().optional(),
  version: z.string().min(1).optional().default("v1"),
  reference: z.string().optional(),
  metadata: z.record(z.string()).optional(),
});

const recordDepositSchema = z.object({
  personId: z.string().min(1),
  transferId: z.string().min(1),
  status: z.string().min(1),
  amount: z.number().finite().positive(),
  chainId: z.number().int().positive().optional().default(BLINK_DEST_CHAIN_ID),
});

const personIdQuerySchema = z.object({ personId: z.string().min(1) });

const serializeDeposit = (d: DepositRow) => ({
  depositId: d.id,
  personId: d.person_id,
  transferId: d.transfer_id,
  amount: Number(d.amount),
  chainId: d.chain_id,
  status: d.status,
  createdAt: d.created_at,
});

export async function blinkRoutes(app: FastifyInstance): Promise<void> {
  // Blink signer endpoint: validates the SignerRequest from @swype-org/deposit and returns
  // the signed payment link payload. Per Blink docs, the signature is ECDSA P-256 + SHA-256
  // over the base64url-encoded payload STRING (never the raw JSON).
  // TODO v2: authenticate callers and verify destination ownership (payer login pending).
  app.post("/blink/sign-payment", async (request, reply) => {
    requireBlink();

    const parsed = signerRequestSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    const { amount, chainId, address, token, callbackScheme, version } = parsed.data;

    if (chainId !== BLINK_DEST_CHAIN_ID || token.toLowerCase() !== BLINK_DEST_TOKEN.toLowerCase()) {
      throw badRequest(
        `destino no soportado: solo Base (${BLINK_DEST_CHAIN_ID}) con USDC ${BLINK_DEST_TOKEN}`,
        "unsupported_destination",
      );
    }
    if (callbackScheme !== null && !ALLOWED_CALLBACK_SCHEMES.has(callbackScheme)) {
      throw badRequest(`callbackScheme no permitido: '${callbackScheme}'`, "validation_error");
    }

    const idempotencyKey = randomUUID();
    const signatureTimestamp = new Date().toISOString();

    // Field order mandated by the Blink signer spec.
    const payloadObject = {
      amount,
      chainId,
      address,
      token,
      idempotencyKey,
      callbackScheme,
      signatureTimestamp,
      version,
    };

    const payload = Buffer.from(JSON.stringify(payloadObject), "utf8").toString("base64url");
    const signer = createSign("SHA256");
    signer.update(payload);
    signer.end();
    const signature = signer.sign(merchantPrivateKeyPem()).toString("base64url");

    return reply
      .header("Cache-Control", "no-store")
      .code(200)
      .send({
        merchantId: config.BLINK_MERCHANT_ID,
        payload,
        signature,
        preview: { amount, chainId, address, token, idempotencyKey },
      });
  });

  // Context for the funding page: resolves the payer's smart account from personId
  // server-side. The page never derives addresses.
  app.get("/deposits/context", async (request, reply) => {
    const parsed = personIdQuerySchema.safeParse(request.query);
    if (!parsed.success) throw badRequest("personId requerido", "validation_error");
    const { personId } = parsed.data;

    const signer = await getSigner();
    const { address } = await signer.getOrCreateAccount(personId);

    return reply.code(200).send({
      personId,
      address,
      chainId: BLINK_DEST_CHAIN_ID,
      token: BLINK_DEST_TOKEN,
    });
  });

  // TODO v2: verificar transfer status server-side contra Blink antes de acreditar nada
  // (Production Checklist). En v1 el depósito es informativo, no acredita balances internos.
  app.post("/deposits/record", async (request, reply) => {
    const parsed = recordDepositSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    const { personId, transferId, status, amount, chainId } = parsed.data;

    const { data, error } = await db
      .from("deposits")
      .upsert(
        {
          person_id: personId,
          transfer_id: transferId,
          status,
          amount,
          chain_id: chainId,
        },
        { onConflict: "transfer_id" },
      )
      .select()
      .single<DepositRow>();
    if (error || !data) {
      request.log.error({ err: error }, "fallo al registrar deposit");
      throw internal("no se pudo registrar el depósito");
    }

    return reply.code(201).send(serializeDeposit(data));
  });

  app.get("/deposits", async (request, reply) => {
    const parsed = personIdQuerySchema.safeParse(request.query);
    if (!parsed.success) throw badRequest("personId requerido", "validation_error");

    const { data } = await db
      .from("deposits")
      .select("*")
      .eq("person_id", parsed.data.personId)
      .order("created_at", { ascending: false });

    return reply.code(200).send((data ?? []).map(serializeDeposit));
  });
}
