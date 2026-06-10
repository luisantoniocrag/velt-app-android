-- ─────────────────────────────────────────────────────────────
-- Velt Backend v1 — esquema (Supabase / PostgreSQL)
-- Ejecutar en el SQL editor de Supabase (o vía psql).
-- ─────────────────────────────────────────────────────────────

-- gen_random_uuid() vive en pgcrypto (suele venir habilitada en Supabase).
create extension if not exists "pgcrypto";

-- 5.0 users ────────────────────────────────────────────────────
-- La persona/cuenta dueña de uno o más comercios. Sus credenciales (teléfono, palma, ...)
-- viven en user_identities; su sesión en refresh_tokens. NO confundir con velt_users (pagador).
create table if not exists users (
  id          uuid primary key default gen_random_uuid(),
  deleted_at  timestamptz,                         -- soft-delete (preserva historial financiero)
  created_at  timestamptz not null default now()
);

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
-- Dueño del comercio + soft-delete.
alter table merchants add column if not exists owner_user_id uuid references users (id);
alter table merchants add column if not exists deleted_at    timestamptz;
-- ENS subname on Sepolia (e.g. cafe-brooklyn.velt.eth), registered fire-and-forget.
alter table merchants add column if not exists ens_name      text;
create index if not exists merchants_owner_idx on merchants (owner_user_id);

-- 5.2 velt_users ───────────────────────────────────────────────
create table if not exists velt_users (
  id                     uuid primary key default gen_random_uuid(),
  person_id              text not null unique,        -- personId del bioserver (string opaco)
  smart_account_address  text not null,
  created_at             timestamptz not null default now()
);

-- person_id es la clave de búsqueda en cada pago.
create unique index if not exists velt_users_person_id_idx on velt_users (person_id);

-- ENS subname on Sepolia (palm-<hash6>.velt.eth), registered fire-and-forget on first payment.
alter table velt_users add column if not exists ens_name text;

-- 5.3 payment_requests ─────────────────────────────────────────
-- Escrow flow: pending → authorizing → held → settled | failed.
-- escrow_tx_hash = hold UserOp; release_tx_hash = release UserOp (tx_hash is its legacy alias);
-- release_after = when the escrow becomes releasable without merchant confirmation.
create table if not exists payment_requests (
  id                uuid primary key default gen_random_uuid(),
  merchant_id       uuid not null references merchants (id),
  amount            numeric(18, 6) not null,          -- USDC, 6 decimales
  status            text not null default 'pending'
                      check (status in ('pending', 'authorizing', 'held', 'settled', 'failed')),
  payer_person_id   text,
  payer_user_id     uuid references velt_users (id),
  tx_hash           text,
  escrow_tx_hash    text,
  release_tx_hash   text,
  release_after     timestamptz,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

-- For tables created before the escrow flow (idempotent).
alter table payment_requests add column if not exists escrow_tx_hash  text;
alter table payment_requests add column if not exists release_tx_hash text;
alter table payment_requests add column if not exists release_after   timestamptz;
alter table payment_requests drop constraint if exists payment_requests_status_check;
alter table payment_requests add constraint payment_requests_status_check
  check (status in ('pending', 'authorizing', 'held', 'settled', 'failed'));

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

-- 5.4b deposits ────────────────────────────────────────────────
-- Payer funding via Blink (destination = payer smart account on Base). Informative in v1:
-- recorded from the client-side DepositResult, never credits internal balances.
create table if not exists deposits (
  id           uuid primary key default gen_random_uuid(),
  person_id    text not null,
  transfer_id  text not null unique,        -- Blink transfer.id (upsert key)
  amount       numeric(18, 6) not null,     -- USD amount requested
  chain_id     bigint not null,
  status       text not null,
  created_at   timestamptz not null default now()
);

create index if not exists deposits_person_idx on deposits (person_id);

-- 5.5 user_identities ──────────────────────────────────────────
-- Identidad de auth ligada a un USUARIO (no a un comercio). (provider, external_id) único: una
-- misma palma/teléfono no puede pertenecer a dos usuarios. Un usuario puede tener varias.
create table if not exists user_identities (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users (id) on delete cascade,
  provider     text not null,            -- 'phone', 'palm', 'google', ...
  external_id  text not null,            -- E.164 (phone), personId (palma), sub (google), ...
  created_at   timestamptz not null default now(),
  unique (provider, external_id)
);

create index if not exists user_identities_user_idx on user_identities (user_id);

-- (La tabla anterior merchant_identities queda inerte; el código usa user_identities.)

-- 5.6 refresh_tokens ───────────────────────────────────────────
-- Refresh tokens opacos, rotativos y revocables. Se guarda solo el sha256, nunca el token crudo.
-- La sesión pertenece al usuario (user_id).
create table if not exists refresh_tokens (
  id          uuid primary key default gen_random_uuid(),
  merchant_id uuid references merchants (id) on delete cascade,  -- legado (nullable); el código usa user_id
  user_id     uuid references users (id) on delete cascade,
  token_hash  text not null unique,
  expires_at  timestamptz not null,
  revoked_at  timestamptz,
  created_at  timestamptz not null default now()
);

-- Para tablas ya creadas antes de añadir user_id (idempotente).
alter table refresh_tokens add column if not exists user_id uuid references users (id) on delete cascade;
-- merchant_id pasa a opcional (las nuevas sesiones usan user_id).
alter table refresh_tokens alter column merchant_id drop not null;

create index if not exists refresh_tokens_user_idx on refresh_tokens (user_id);

-- Refrescar el cache de esquema de PostgREST tras DDL (evita PGRST204 al reaplicar).
notify pgrst, 'reload schema';
