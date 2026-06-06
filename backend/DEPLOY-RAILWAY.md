# Deploy en Railway

El backend es un servidor Node always-on (Fastify + WebSockets + liquidación en background),
así que necesita un proceso permanente — no serverless. Railway encaja directo.

> **Una sola réplica.** El registro de sockets (`lib/events.ts`) y el mapa `address→subject`
> del `LocalSigner` viven en memoria. `railway.json` fija `numReplicas: 1`; no lo subas.

## 1. Crear el servicio

1. railway.app → **New Project** → **Deploy from GitHub repo** → elige este repo.
2. En el servicio: **Settings → Root Directory = `backend`** (es un monorepo; Railway debe
   construir solo esa carpeta).
3. El build lo define [`railway.json`](railway.json): Nixpacks detecta Node (fijado a 20 por
   `.nvmrc` + `engines`), corre `npm run build` (`tsc → dist/`) y arranca con `npm start`
   (`node dist/index.js`). Healthcheck en `/health`.

## 2. Variables de entorno

**Settings → Variables.** Copia los valores de tu `.env` local (no commiteado). `PORT` lo
inyecta Railway automáticamente — **no la pongas**.

Requeridas (el server no arranca sin ellas — validación zod en `src/config.ts`):

| Var | Notas |
|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | la misma DB Supabase que ya usas |
| `ARC_RPC_URL`, `ARC_CHAIN_ID` | red Arc |
| `USDC_CONTRACT_ADDRESS` | contrato USDC en Arc |
| `ERC4337_BUNDLER_URL`, `ERC4337_ENTRYPOINT_ADDRESS` | bundler (Pimlico) + EntryPoint v0.7 |
| `SIGNER_BACKEND` | `local` |
| `LOCAL_SIGNER_MASTER_KEY` | **secreto — controla todas las llaves derivadas** |
| `JWT_SECRET` | **secreto — controla las sesiones**; ≥32 chars |
| `BIOSERVER_CLIENT_ID`, `BIOSERVER_SHARED_SECRET` | bioserver (palma) |

Opcionales (tienen default, solo si quieres cambiarlas): `ACCESS_TOKEN_TTL_SECONDS` (900),
`REFRESH_TOKEN_TTL_SECONDS` (2592000), `BIOSERVER_URL` (`https://openpalm.io/admin-app/`).

## 3. Dominio público

**Settings → Networking → Generate Domain** → te da `https://<algo>.up.railway.app`.

- HTTP: `https://<dominio>/api/v1/...`
- WebSocket: `wss://<dominio>/ws/payments/<id>` y `.../ws/withdrawals/<id>`.
  El backend devuelve `wsUrl` como ruta relativa, así que el cliente solo antepone `wss://<dominio>`.

## 4. Antes de la primera petición real

- Aplica `src/db/schema.sql` en el editor SQL de Supabase (idempotente) si la DB aún no tiene
  las tablas nuevas (`withdrawals`, `merchant_identities`, `refresh_tokens`).
- Verifica: `GET https://<dominio>/health` → `{ "ok": true }`.

## Deploy por CLI (opcional)

```bash
npm i -g @railway/cli
railway login
railway link            # selecciona el proyecto
railway up              # sube y despliega desde ./backend
```
