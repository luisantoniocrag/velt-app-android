import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import WebSocket from "ws";
import { config } from "../config.js";

// Cliente de Supabase Auth (GoTrue) para el login por teléfono (OTP por SMS). Usa la anon key,
// no la service key: los flujos OTP están pensados para el rol anónimo. Es independiente del
// cliente PostgREST de `db/client.ts`.
let authClient: SupabaseClient | null = null;

function client(): SupabaseClient {
  if (!config.SUPABASE_ANON_KEY) {
    throw new Error("SUPABASE_ANON_KEY no configurada: requerida para el login por teléfono");
  }
  if (!authClient) {
    authClient = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- shim de interop ws → WebSocketLike
      realtime: { transport: WebSocket as any },
    });
  }
  return authClient;
}

// Normaliza a E.164 (`+<código país><número>`) y valida. El resultado es el `external_id` estable
// que liga el teléfono a un usuario en `user_identities`.
export function normalizePhone(raw: string): string {
  const trimmed = raw.trim();
  if (!/^\+[1-9]\d{6,14}$/.test(trimmed)) {
    throw new Error("teléfono inválido; usa formato E.164, p. ej. +5215512345678");
  }
  return trimmed;
}

// Canal de entrega del OTP. 'whatsapp' solo funciona con Twilio como proveedor en Supabase
// (y un sender de WhatsApp aprobado); 'sms' funciona con cualquier proveedor.
export type OtpChannel = "sms" | "whatsapp";

// Paso 1: dispara el código por SMS o WhatsApp. Requiere un proveedor configurado en Supabase
// (Authentication → Providers → Phone). shouldCreateUser por defecto = true: no usamos la tabla
// auth.users de Supabase como fuente de verdad (eso vive en user_identities), solo su entrega
// y verificación de OTP.
export async function sendPhoneOtp(phone: string, channel: OtpChannel = "sms"): Promise<void> {
  const { error } = await client().auth.signInWithOtp({ phone, options: { channel } });
  if (error) throw new Error(`no se pudo enviar el OTP: ${error.message}`);
}

// Paso 2: verifica el código. Lanza si es inválido/expirado.
export async function verifyPhoneOtp(phone: string, code: string): Promise<void> {
  const { data, error } = await client().auth.verifyOtp({ phone, token: code, type: "sms" });
  if (error || !data.user) throw new Error("OTP inválido o expirado");
}
