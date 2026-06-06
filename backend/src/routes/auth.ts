import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { db, type MerchantIdentityRow } from "../db/client.js";
import { badRequest, conflict, internal, unauthorized } from "../lib/errors.js";
import { getAuthProvider, type AuthIdentity } from "../auth/provider.js";
import { issueSession, revokeRefreshToken, rotateRefreshToken } from "../auth/tokens.js";
import { createMerchant } from "./merchants.js";

const evmAddress = z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida");

const registerSchema = z.object({
  name: z.string().min(1),
  provider: z.string().min(1),
  credentials: z.unknown(),
  smartAccountAddress: evmAddress.optional(),
});

const loginSchema = z.object({
  provider: z.string().min(1),
  credentials: z.unknown(),
});

const refreshSchema = z.object({ refreshToken: z.string().min(1) });

export async function authRoutes(app: FastifyInstance): Promise<void> {
  // Self-signup: verifica la identidad (palma → bioserver), crea el comerciante, lo liga y devuelve sesión.
  app.post("/auth/register", async (request, reply) => {
    const parsed = registerSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const identity = await authenticateWith(parsed.data.provider, parsed.data.credentials, request.log);

    const existing = await findIdentity(identity);
    if (existing) {
      throw conflict("esa identidad ya está registrada; usa /auth/login", "identity_already_registered");
    }

    const merchant = await createMerchant({
      name: parsed.data.name,
      smartAccountAddress: parsed.data.smartAccountAddress,
    });

    const { error } = await db.from("merchant_identities").insert({
      merchant_id: merchant.id,
      provider: identity.provider,
      external_id: identity.externalId,
    });
    if (error) {
      request.log.error({ err: error }, "fallo al ligar identidad");
      throw internal("no se pudo completar el registro");
    }

    const session = await issueSession(merchant.id);
    return reply.code(201).send({
      merchant: {
        id: merchant.id,
        name: merchant.name,
        smartAccountAddress: merchant.smart_account_address,
        custodial: merchant.custodial,
      },
      ...session,
    });
  });

  // Login: verifica la identidad y, si está ligada a un comerciante, emite sesión.
  app.post("/auth/login", async (request, reply) => {
    const parsed = loginSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const identity = await authenticateWith(parsed.data.provider, parsed.data.credentials, request.log);

    const linked = await findIdentity(identity);
    if (!linked) {
      throw unauthorized("identidad no registrada; usa /auth/register", "unknown_identity");
    }

    const session = await issueSession(linked.merchant_id);
    return reply.code(200).send({ merchantId: linked.merchant_id, ...session });
  });

  app.post("/auth/refresh", async (request, reply) => {
    const parsed = refreshSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    try {
      const session = await rotateRefreshToken(parsed.data.refreshToken);
      return reply.code(200).send(session);
    } catch {
      throw unauthorized("refresh token inválido o expirado", "invalid_refresh_token");
    }
  });

  app.post("/auth/logout", async (request, reply) => {
    const parsed = refreshSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    await revokeRefreshToken(parsed.data.refreshToken);
    return reply.code(204).send();
  });
}

// Resuelve el proveedor y autentica. Provider desconocido/no implementado → 400; fallo de la
// credencial (palma no reconocida, bioserver caído) → 401.
async function authenticateWith(
  provider: string,
  credentials: unknown,
  log: FastifyInstance["log"],
): Promise<AuthIdentity> {
  let authProvider;
  try {
    authProvider = await getAuthProvider(provider);
  } catch {
    throw badRequest(`proveedor de auth no soportado: '${provider}'`, "unsupported_provider");
  }
  try {
    return await authProvider.authenticate(credentials, { log });
  } catch (err) {
    log.warn({ err, provider }, "fallo de autenticación");
    throw unauthorized("no se pudo autenticar", "auth_failed");
  }
}

async function findIdentity(identity: AuthIdentity): Promise<MerchantIdentityRow | null> {
  const { data } = await db
    .from("merchant_identities")
    .select("*")
    .eq("provider", identity.provider)
    .eq("external_id", identity.externalId)
    .maybeSingle<MerchantIdentityRow>();
  return data ?? null;
}
