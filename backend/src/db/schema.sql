-- ─────────────────────────────────────────────────────────────
-- Velt Backend v1 — esquema (Supabase / PostgreSQL)
-- Ejecutar en el SQL editor de Supabase (o vía psql).
-- ─────────────────────────────────────────────────────────────

-- gen_random_uuid() vive en pgcrypto (suele venir habilitada en Supabase).
create extension if not exists "pgcrypto";

-- 5.1 merchants ────────────────────────────────────────────────
create table if not exists merchants (
  id                     uuid primary key default gen_random_uuid(),
  name                   text not null,
  smart_account_address  text not null,
  created_at             timestamptz not null default now()
);

-- 5.2 velt_users ───────────────────────────────────────────────
create table if not exists velt_users (
  id                     uuid primary key default gen_random_uuid(),
  person_id              text not null unique,        -- personId del bioserver (string opaco)
  smart_account_address  text not null,
  created_at             timestamptz not null default now()
);

-- person_id es la clave de búsqueda en cada pago.
create unique index if not exists velt_users_person_id_idx on velt_users (person_id);

-- 5.3 payment_requests ─────────────────────────────────────────
create table if not exists payment_requests (
  id                uuid primary key default gen_random_uuid(),
  merchant_id       uuid not null references merchants (id),
  amount            numeric(18, 6) not null,          -- USDC, 6 decimales
  status            text not null default 'pending'
                      check (status in ('pending', 'authorizing', 'settled', 'failed')),
  payer_person_id   text,
  payer_user_id     uuid references velt_users (id),
  tx_hash           text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create index if not exists payment_requests_status_idx on payment_requests (status);

-- Mantener updated_at al día en cada UPDATE.
create or replace function set_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

drop trigger if exists payment_requests_set_updated_at on payment_requests;
create trigger payment_requests_set_updated_at
  before update on payment_requests
  for each row execute function set_updated_at();
