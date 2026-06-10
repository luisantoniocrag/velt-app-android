import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import WebSocket from "ws";
import { config } from "../config.js";

// Service key: acceso server-side, salta RLS. No usamos realtime, pero supabase-js
// construye su RealtimeClient siempre y Node < 22 no trae WebSocket nativo; le pasamos
// el de `ws` para evitar el crash.
export const db: SupabaseClient = createClient(
  config.SUPABASE_URL,
  config.SUPABASE_SERVICE_KEY,
  {
    auth: { persistSession: false, autoRefreshToken: false },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- shim de interop ws → WebSocketLike
    realtime: { transport: WebSocket as any },
  },
);

// Tipos de fila (espejo de schema.sql).
export type PaymentStatus = "pending" | "authorizing" | "held" | "settled" | "failed";
export type WithdrawalStatus = "pending" | "processing" | "settled" | "failed";

export interface UserRow {
  id: string;
  deleted_at: string | null;
  created_at: string;
}

export interface MerchantRow {
  id: string;
  name: string;
  smart_account_address: string;
  custodial: boolean; // true → cuenta derivada por el backend (puede retirar)
  owner_user_id: string | null;
  deleted_at: string | null;
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
  escrow_tx_hash: string | null;
  release_tx_hash: string | null;
  release_after: string | null;
  created_at: string;
  updated_at: string;
}

export interface WithdrawalRow {
  id: string;
  merchant_id: string;
  to_address: string;
  amount: string | number; // numeric → suele llegar como string desde PostgREST
  status: WithdrawalStatus;
  tx_hash: string | null;
  reason: string | null;
  created_at: string;
  updated_at: string;
}

export interface UserIdentityRow {
  id: string;
  user_id: string;
  provider: string; // 'phone', 'palm', 'google', ...
  external_id: string; // E.164 (phone), personId (palma), sub (google), ...
  created_at: string;
}

export interface RefreshTokenRow {
  id: string;
  user_id: string;
  token_hash: string; // sha256 del token crudo
  expires_at: string;
  revoked_at: string | null;
  created_at: string;
}
