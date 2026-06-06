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
  -- true = la cuenta la derivó el backend (controla la llave → puede retirar).
  -- false = dirección externa traída por el comerciante (el backend NO tiene la llave).
  custodial              boolean not null default false,
  created_at             timestamptz not null default now()
);

-- Para tablas ya creadas antes de añadir la columna (idempotente).
alter table merchants add column if not exists custodial boolean not null default false;

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

-- 5.4 withdrawals ──────────────────────────────────────────────
-- Retiro de fondos de la smart account de un comerciante hacia una dirección externa.
create table if not exists withdrawals (
  id                uuid primary key default gen_random_uuid(),
  merchant_id       uuid not null references merchants (id),
  to_address        text not null,
  amount            numeric(18, 6) not null,          -- USDC, 6 decimales
  status            text not null default 'pending'
                      check (status in ('pending', 'processing', 'settled', 'failed')),
  tx_hash           text,
  reason            text,                             -- motivo del fallo (si status='failed')
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create index if not exists withdrawals_status_idx on withdrawals (status);

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

drop trigger if exists withdrawals_set_updated_at on withdrawals;
create trigger withdrawals_set_updated_at
  before update on withdrawals
  for each row execute function set_updated_at();

-- 5.5 merchant_identities ──────────────────────────────────────
-- Identidad de auth ligada a un comerciante. (provider, external_id) único: una misma palma
-- (o cuenta Google a futuro) no puede pertenecer a dos comerciantes.
create table if not exists merchant_identities (
  id           uuid primary key default gen_random_uuid(),
  merchant_id  uuid not null references merchants (id) on delete cascade,
  provider     text not null,            -- 'palm', 'google', ...
  external_id  text not null,            -- personId (palma), sub (google), ...
  created_at   timestamptz not null default now(),
  unique (provider, external_id)
);

create index if not exists merchant_identities_merchant_idx on merchant_identities (merchant_id);

-- 5.6 refresh_tokens ───────────────────────────────────────────
-- Refresh tokens opacos, rotativos y revocables. Se guarda solo el sha256, nunca el token crudo.
create table if not exists refresh_tokens (
  id          uuid primary key default gen_random_uuid(),
  merchant_id uuid not null references merchants (id) on delete cascade,
  token_hash  text not null unique,
  expires_at  timestamptz not null,
  revoked_at  timestamptz,
  created_at  timestamptz not null default now()
);

create index if not exists refresh_tokens_merchant_idx on refresh_tokens (merchant_id);

-- Refrescar el cache de esquema de PostgREST tras DDL (evita PGRST204 al reaplicar).
notify pgrst, 'reload schema';
