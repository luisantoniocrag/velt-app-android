import { config } from "../config.js";

// Login por teléfono vía Stytch (OTP por SMS/WhatsApp). Reemplaza a Supabase Phone Auth / Twilio.
// Flujo en dos llamadas: login_or_create (envía y devuelve phone_id) → authenticate (verifica con
// method_id = phone_id). El backend mantiene el contrato hacia la app: {phone} → {phone, code}.

const BASE_URL = config.STYTCH_ENV === "live" ? "https://api.stytch.com" : "https://test.stytch.com";

function authHeader(): string {
  if (!config.STYTCH_PROJECT_ID || !config.STYTCH_SECRET) {
    throw new Error("STYTCH_PROJECT_ID/STYTCH_SECRET no configuradas: requeridas para el login por teléfono");
  }
  const basic = Buffer.from(`${config.STYTCH_PROJECT_ID}:${config.STYTCH_SECRET}`).toString("base64");
  return `Basic ${basic}`;
}

export type OtpChannel = "sms" | "whatsapp";

// Normaliza a E.164 y valida. El resultado es el `external_id` estable que liga el teléfono al usuario.
export function normalizePhone(raw: string): string {
  const trimmed = raw.trim();
  if (!/^\+[1-9]\d{6,14}$/.test(trimmed)) {
    throw new Error("teléfono inválido; usa formato E.164, p. ej. +5215512345678");
  }
  return trimmed;
}

// phone_id (method_id) que devuelve el envío y exige la verificación. En memoria con TTL: el OTP vive
// minutos. Single-instance (como el registro de sockets); un redeploy entre enviar y verificar lo pierde.
const PENDING_TTL_MS = 10 * 60 * 1000;
const pending = new Map<string, { methodId: string; expiresAt: number }>();

async function stytchPost(path: string, body: unknown): Promise<Record<string, unknown>> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: { Authorization: authHeader(), "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    const msg = (data.error_message as string) || (data.error_type as string) || `HTTP ${res.status}`;
    throw new Error(`stytch: ${msg}`);
  }
  return data;
}

// Paso 1: envía el código (crea el usuario Stytch si no existe — no lo usamos como fuente de verdad).
export async function sendPhoneOtp(phone: string, channel: OtpChannel = "whatsapp"): Promise<void> {
  const path = channel === "whatsapp" ? "/v1/otps/whatsapp/login_or_create" : "/v1/otps/sms/login_or_create";
  const data = await stytchPost(path, { phone_number: phone });
  const methodId = data.phone_id as string | undefined;
  if (!methodId) throw new Error("stytch: respuesta sin phone_id");
  pending.set(phone, { methodId, expiresAt: Date.now() + PENDING_TTL_MS });
}

// Paso 2: verifica el código contra el method_id pendiente. Lanza si no hay pendiente o es inválido.
export async function verifyPhoneOtp(phone: string, code: string): Promise<void> {
  const entry = pending.get(phone);
  if (!entry || entry.expiresAt < Date.now()) {
    pending.delete(phone);
    throw new Error("no hay un OTP pendiente para ese teléfono (expiró o no se solicitó)");
  }
  await stytchPost("/v1/otps/authenticate", { method_id: entry.methodId, code });
  pending.delete(phone);
}
