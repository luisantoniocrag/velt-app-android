import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type Address } from "viem";
import { db, type UserIdentityRow, type UserRow } from "../db/client.js";
import { badRequest, conflict, internal, notFound, unauthorized } from "../lib/errors.js";
import { getAuthProvider, type AuthIdentity } from "../auth/provider.js";
import { issueSession, revokeRefreshToken, rotateRefreshToken } from "../auth/tokens.js";
import { normalizePhone, sendPhoneOtp } from "../auth/supabaseAuth.js";
import { requireAuth } from "../auth/middleware.js";
import { getUsdcBalance } from "../chain/usdc.js";

const credentialAuthSchema = z.object({
  provider: z.string().min(1),
  credentials: z.unknown(),
});

const refreshSchema = z.object({ refreshToken: z.string().min(1) });

const phoneOtpSchema = z.object({
  phone: z.string().min(1),
  channel: z.enum(["sms", "whatsapp"]).optional(),
});

export async function authRoutes(app: FastifyInstance): Promise<void> {
  // Paso previo del login por teléfono: dispara el SMS/WhatsApp con el código (Supabase Phone Auth).
  // Luego se usa /auth/register o /auth/login con provider:"phone", credentials:{ phone, code }.
  app.post("/auth/phone/otp", async (request, reply) => {
    const parsed = phoneOtpSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }
    let phone: string;
    try {
      phone = normalizePhone(parsed.data.phone);
    } catch (err) {
      throw badRequest((err as Error).message, "invalid_phone");
    }
    try {
      await sendPhoneOtp(phone, parsed.data.channel ?? "sms");
    } catch (err) {
      request.log.warn({ err }, "fallo al enviar OTP");
      throw internal("no se pudo enviar el código");
    }
    return reply.code(204).send();
  });

  // Self-signup: verifica la identidad y crea el USUARIO (no un comercio). Los comercios se crean
  // después con POST /merchants.
  app.post("/auth/register", async (request, reply) => {
    const parsed = credentialAuthSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const identity = await authenticateWith(parsed.data.provider, parsed.data.credentials, request.log);

    const existing = await findIdentity(identity);
    if (existing) {
      throw conflict("esa identidad ya está registrada; usa /auth/login", "identity_already_registered");
    }

    const { data: user, error: userErr } = await db
      .from("users")
      .insert({})
      .select()
      .single<UserRow>();
    if (userErr || !user) {
      request.log.error({ err: userErr }, "fallo al crear usuario");
      throw internal("no se pudo completar el registro");
    }

    const { error: idErr } = await db.from("user_identities").insert({
      user_id: user.id,
      provider: identity.provider,
      external_id: identity.externalId,
    });
    if (idErr) {
      request.log.error({ err: idErr }, "fallo al ligar identidad");
      throw internal("no se pudo completar el registro");
    }

    const session = await issueSession(user.id);
    return reply.code(201).send({ user: { id: user.id }, ...session });
  });

  // Login: verifica la identidad y, si está ligada a un usuario, emite sesión.
  app.post("/auth/login", async (request, reply) => {
    const parsed = credentialAuthSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const identity = await authenticateWith(parsed.data.provider, parsed.data.credentials, request.log);

    const linked = await findIdentity(identity);
    if (!linked) {
      throw unauthorized("identidad no registrada; usa /auth/register", "unknown_identity");
    }

    const session = await issueSession(linked.user_id);
    return reply.code(200).send({ userId: linked.user_id, ...session });
  });

  // Añadir una identidad (p. ej. la palma) a la cuenta ya autenticada.
  app.post("/auth/link", { preHandler: requireAuth }, async (request, reply) => {
    const parsed = credentialAuthSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const identity = await authenticateWith(parsed.data.provider, parsed.data.credentials, request.log);

    const existing = await findIdentity(identity);
    if (existing) {
      if (existing.user_id === request.userId) {
        return reply.code(200).send({ linked: true, provider: identity.provider });
      }
      throw conflict("esa identidad ya pertenece a otra cuenta", "identity_in_use");
    }

    const { error } = await db.from("user_identities").insert({
      user_id: request.userId,
      provider: identity.provider,
      external_id: identity.externalId,
    });
    if (error) {
      request.log.error({ err: error }, "fallo al vincular identidad");
      throw internal("no se pudo vincular la identidad");
    }

    return reply.code(201).send({ linked: true, provider: identity.provider });
  });

  // Quitar una identidad (p. ej. la palma). No deja la cuenta sin ningún login.
  app.delete<{ Params: { provider: string } }>(
    "/auth/identities/:provider",
    { preHandler: requireAuth },
    async (request, reply) => {
      const { data: identities } = await db
        .from("user_identities")
        .select("id, provider")
        .eq("user_id", request.userId);
      const list = identities ?? [];

      const target = list.filter((i) => i.provider === request.params.provider);
      if (target.length === 0) throw notFound("identidad no encontrada", "identity_not_found");
      if (list.length - target.length === 0) {
        throw conflict("no puedes quitar tu única identidad de acceso", "cannot_remove_last_identity");
      }

      await db
        .from("user_identities")
        .delete()
        .eq("user_id", request.userId)
        .eq("provider", request.params.provider);

      return reply.code(204).send();
    },
  );

  // Perfil: ¿soy nuevo?, mis identidades y mis comercios.
  app.get("/auth/me", { preHandler: requireAuth }, async (request, reply) => {
    const { data: identities } = await db
      .from("user_identities")
      .select("provider")
      .eq("user_id", request.userId);

    const { data: merchants } = await db
      .from("merchants")
      .select("id, name, smart_account_address, custodial")
      .eq("owner_user_id", request.userId)
      .is("deleted_at", null);

    const list = merchants ?? [];
    return reply.code(200).send({
      userId: request.userId,
      isNew: list.length === 0,
      identities: (identities ?? []).map((i) => ({ provider: i.provider })),
      merchants: list.map((m) => ({
        id: m.id,
        name: m.name,
        smartAccountAddress: m.smart_account_address,
        custodial: m.custodial,
      })),
    });
  });

  // Eliminar mi cuenta. Bloquea si algún comercio custodial tiene saldo USDC (exige retiro previo).
  app.delete("/auth/me", { preHandler: requireAuth }, async (request, reply) => {
    const { data: merchants } = await db
      .from("merchants")
      .select("id, smart_account_address, custodial")
      .eq("owner_user_id", request.userId)
      .is("deleted_at", null);

    for (const m of merchants ?? []) {
      if (!m.custodial) continue;
      const balance = await getUsdcBalance(m.smart_account_address as Address);
      if (balance > 0n) {
        throw conflict(
          "tienes comercios con saldo USDC; retíralo antes de eliminar la cuenta",
          "must_withdraw_first",
        );
      }
    }

    const now = new Date().toISOString();
    await db.from("merchants").update({ deleted_at: now }).eq("owner_user_id", request.userId).is("deleted_at", null);
    await db.from("user_identities").delete().eq("user_id", request.userId);
    await db.from("refresh_tokens").update({ revoked_at: now }).eq("user_id", request.userId).is("revoked_at", null);
    await db.from("users").update({ deleted_at: now }).eq("id", request.userId);

    return reply.code(204).send();
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
// credencial (palma no reconocida, OTP inválido) → 401.
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

async function findIdentity(identity: AuthIdentity): Promise<UserIdentityRow | null> {
  const { data } = await db
    .from("user_identities")
    .select("*")
    .eq("provider", identity.provider)
    .eq("external_id", identity.externalId)
    .maybeSingle<UserIdentityRow>();
  return data ?? null;
}
