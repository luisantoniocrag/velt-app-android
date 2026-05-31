import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import WebSocket from "ws";
import { config } from "../config.js";

/**
 * Cliente Supabase con la service key (acceso server-side, salta RLS).
 * Una sola instancia compartida en todo el proceso.
 *
 * No usamos realtime, pero supabase-js construye su RealtimeClient siempre y en
 * Node < 22 no hay WebSocket nativo; le pasamos el de `ws` para evitar el crash.
 */
export const db: SupabaseClient = createClient(
  config.SUPABASE_URL,
  config.SUPABASE_SERVICE_KEY,
  {
    auth: { persistSession: false, autoRefreshToken: false },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- shim de interop ws → WebSocketLike
    realtime: { transport: WebSocket as any },
  },
);

// ── Tipos de fila (espejo de schema.sql) ──────────────────────────────
export type PaymentStatus = "pending" | "authorizing" | "settled" | "failed";

export interface MerchantRow {
  id: string;
  name: string;
  smart_account_address: string;
  created_at: string;
}

export interface VeltUserRow {
  id: string;
  person_id: string;
  smart_account_address: string;
  created_at: string;
}

export interface PaymentRequestRow {
  id: string;
  merchant_id: string;
  amount: string | number; // numeric → suele llegar como string desde PostgREST
  status: PaymentStatus;
  payer_person_id: string | null;
  payer_user_id: string | null;
  tx_hash: string | null;
  created_at: string;
  updated_at: string;
}
