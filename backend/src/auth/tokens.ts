import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { config } from "../config.js";
import { db, type RefreshTokenRow } from "../db/client.js";

// JWT HS256 hecho a mano (cero dependencias, consistente con el HMAC del bioserver) para el access
// token, + refresh tokens opacos rotativos y revocables en `refresh_tokens`.

const HEADER = { alg: "HS256", typ: "JWT" } as const;

const b64urlJson = (obj: unknown): string => Buffer.from(JSON.stringify(obj), "utf8").toString("base64url");
const sign = (data: string): string => createHmac("sha256", config.JWT_SECRET).update(data).digest("base64url");
const hashToken = (raw: string): string => createHash("sha256").update(raw).digest("hex");

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number; // segundos de vida del access token
}

export function signAccessToken(userId: string): string {
  const now = Math.floor(Date.now() / 1000);
  const head = b64urlJson(HEADER);
  const body = b64urlJson({
    sub: userId,
    type: "access",
    iat: now,
    exp: now + config.ACCESS_TOKEN_TTL_SECONDS,
  });
  return `${head}.${body}.${sign(`${head}.${body}`)}`;
}

export function verifyAccessToken(token: string): { userId: string } {
  const [head, body, sig] = token.split(".");
  if (!head || !body || !sig) throw new Error("token malformado");

  // alg fijo HS256: no se confía en el `alg` del token (evita alg-confusion).
  const expected = Buffer.from(sign(`${head}.${body}`));
  const actual = Buffer.from(sig);
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    throw new Error("firma inválida");
  }

  const header = JSON.parse(Buffer.from(head, "base64url").toString("utf8"));
  if (header.alg !== "HS256") throw new Error("alg no soportado");

  const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8"));
  if (payload.type !== "access") throw new Error("tipo de token inválido");
  if (typeof payload.sub !== "string") throw new Error("sub ausente");
  if (typeof payload.exp !== "number" || payload.exp < Math.floor(Date.now() / 1000)) {
    throw new Error("token expirado");
  }
  return { userId: payload.sub };
}

async function issueRefreshToken(userId: string): Promise<string> {
  const raw = randomBytes(32).toString("base64url");
  const expiresAt = new Date(Date.now() + config.REFRESH_TOKEN_TTL_SECONDS * 1000).toISOString();
  const { error } = await db
    .from("refresh_tokens")
    .insert({ user_id: userId, token_hash: hashToken(raw), expires_at: expiresAt });
  if (error) throw new Error("no se pudo emitir el refresh token");
  return raw;
}

export async function issueSession(userId: string): Promise<TokenPair> {
  const accessToken = signAccessToken(userId);
  const refreshToken = await issueRefreshToken(userId);
  return { accessToken, refreshToken, expiresIn: config.ACCESS_TOKEN_TTL_SECONDS };
}

export async function rotateRefreshToken(raw: string): Promise<TokenPair> {
  const { data: row } = await db
    .from("refresh_tokens")
    .select("*")
    .eq("token_hash", hashToken(raw))
    .maybeSingle<RefreshTokenRow>();
  if (!row) throw new Error("refresh token inválido");

  if (row.revoked_at) {
    // Reuso de un token ya revocado → posible robo: revoca toda la familia del usuario.
    await db
      .from("refresh_tokens")
      .update({ revoked_at: new Date().toISOString() })
      .eq("user_id", row.user_id)
      .is("revoked_at", null);
    throw new Error("refresh token revocado");
  }
  if (new Date(row.expires_at).getTime() < Date.now()) throw new Error("refresh token expirado");

  // Rotación: revoca el viejo y emite un par nuevo.
  await db.from("refresh_tokens").update({ revoked_at: new Date().toISOString() }).eq("id", row.id);
  return issueSession(row.user_id);
}

export async function revokeRefreshToken(raw: string): Promise<void> {
  await db
    .from("refresh_tokens")
    .update({ revoked_at: new Date().toISOString() })
    .eq("token_hash", hashToken(raw))
    .is("revoked_at", null);
}
