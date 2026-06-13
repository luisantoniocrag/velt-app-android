# Velt Backend — Flujos actuales

> Mapa de contexto para agentes/devs: qué existe hoy, cómo funciona cada flujo, en qué cadena
> corre, y cómo probarlo. La fuente de verdad es el código en `src/`. Complementa a
> [`README.md`](README.md) (setup) e [`INTEGRATION-ANDROID.md`](INTEGRATION-ANDROID.md).

## Qué es

Servicio Fastify (Node 20 + TS strict) que liquida **pagos USDC** entre un pagador (identificado
por palma) y un comercio, con **escrow condicional** on-chain. Cada actor tiene una **wallet de
Dynamic (TSS-MPC)** que el backend orquesta. Sin UI; sin matching biométrico (eso es el bioserver).

- Persistencia: **Supabase** (PostgREST, service key). Esquema en `src/db/schema.sql`.
- Firma on-chain: interfaz `Signer` (`src/chain/signer.ts`), backend `dynamic` (Server Wallets).
- Tiempo real: **WebSocket** para pagos y retiros; `GET` de fallback siempre.
- Config: **zod** valida el `.env` al arranque (si falta algo, no arranca).
- Prefijo: todo en **`/api/v1`** (salvo `/health`, `/fund` y `/ws/...`). Build: `npm run typecheck`.

## ⛓️ Es multi-cadena (a propósito)

| Pieza | Cadena | Por qué |
|---|---|---|
| Wallets, pagos, escrow, retiros | **Arc** | el core de liquidación USDC |
| Nombres ENS (subnames) | **Sepolia** | ENS vive en L1; el nombre solo apunta a la dirección (misma en cualquier EVM) |
| Funding del pagador (Blink) | **Base** | Blink deposita ahí; **no hay puente a Arc** (CCTP es stretch) |
| Retiro privado (Unlink) | **arc-testnet** | pool shielded de Unlink, misma cadena que el core |

## Glosario — los tres actores

| Actor | Tabla | Qué es |
|---|---|---|
| **Usuario** | `users` | Persona dueña de comercios. Único con login (palma/teléfono → JWT). |
| **Comercio** | `merchants` | Negocio que cobra. Pertenece a un usuario. Tiene wallet Dynamic que **recibe** los pagos. |
| **Pagador** | `velt_users` | Quien paga con la palma. **Sin login**: solo `person_id` (del bioserver) → su wallet Dynamic. |

> Una misma palma puede ser usuario (en la app) y pagador (en el punto de venta): son **entidades
> distintas con wallets distintas**. La wallet **NO** nace al loguearse — nace al **crear el
> comercio** (wallet del comercio) o al **primer pago** (wallet del pagador).

## 🔑 Wallets: Dynamic Server Wallets (`SIGNER_BACKEND=dynamic`)

`src/chain/dynamicSigner.ts`. Cada `subject` (`merchant:<id>` o `personId` del pagador, más
`operator` del escrow) mapea a **una EOA MPC de Dynamic**, persistida en `dynamic_wallets`
(`account_address` + `wallet_metadata` + `server_key_shares`). El backend firma vía la API de
Dynamic (el server-share participa en una ceremonia MPC; la llave privada nunca existe completa).

- **Son EOAs → necesitan gas nativo en Arc** para transaccionar (a diferencia de las antiguas
  smart accounts ERC-4337). El backend **imprime cada wallet nueva** al crearla:
  `[dynamic] nueva wallet '<subject>' → 0x... (fondear gas)`. El fondeo es manual (hackathon).
- La interfaz `Signer` hace el swap **transparente**: pagos/escrow/retiros no cambian.
- Otros backends: `local` (LocalSigner, legado), `privy`/`turnkey` (stubs).
- **Gotcha**: `signAndSendCalls` rehidrata la wallet por `from`; los callers llaman
  `getOrCreateAccount(subject)` **antes** de firmar (mapa en memoria + lookup en DB).

## 🤝 Autenticación de usuario (`src/auth/`, `routes/auth.ts`)

Login-or-create (no hay register vs login). En la app, **la palma es el login principal**; el
teléfono se agrega como **recuperación**.

- **Palma**: `POST /auth/verify { provider:"palm", credentials:{ template } }` → bioserver
  identify → `personId` = `externalId`.
- **Teléfono (OTP Stytch)**: `POST /auth/phone/otp { phone, channel? }` (default whatsapp) →
  luego `POST /auth/verify { provider:"phone", credentials:{ phone, code } }`.
- `verify` busca `(provider, externalId)` en `user_identities`: existe → sesión; no → crea
  `users` + identidad (`userCreated:true`). **No crea comercio** (se crea a mano después).
- `POST /auth/link` agrega una 2ª identidad (p. ej. teléfono de recuperación tras la palma).
  `DELETE /auth/identities/:provider` la quita (no la última). `GET /auth/me` = perfil
  (`isNew`, identidades, comercios con `ensName`). `DELETE /auth/me` = soft-delete con guardia de saldo.
- **Tokens** (`auth/tokens.ts`): access JWT HS256 (15m) + refresh opaco rotativo (30d, hasheado,
  reuso de revocado → revoca la familia). `requireAuth` protege comercios/withdraw/`/auth/*`.
  **Los pagos NO requieren auth** (el pagador no tiene login).

## 🏪 Comercios (`routes/merchants.ts`)

CRUD autenticado vía `loadOwnedMerchant` (exige activo + propiedad). Al **crear** un comercio sin
`smartAccountAddress` → deriva su **wallet Dynamic** (`custodial=true`) y dispara **ENS**
fire-and-forget. Con dirección externa → `custodial=false` (recibe, pero no retira: `409`).
`GET /merchants/:id` añade `usdcBalance` on-chain + `ensName`.

## 💸 Flujo de pago con ESCROW (`routes/payments.ts`)

Estado: `pending → authorizing → held → settled | failed`. Sin auth (salvo `/confirm`).

1. **`POST /payments/initiate { merchantId, amount }`** → fila `pending`, responde `201 { paymentId, wsUrl }`. La app abre el WS **antes** de autorizar.
2. **`POST /payments/authorize { paymentId, personId }`** (el `personId` lo resuelve la app contra
   el bioserver, client-side) → claim atómico `pending→authorizing`, responde **`202`**, dispara
   `settlePayment` en background.
3. **`settlePayment`** (nunca lanza): `getOrCreateAccount(personId)` (wallet Dynamic del pagador)
   → `ensureVeltUser` (+ ENS `palm-<hash>.velt.eth` fire-and-forget) → **UserOp batcheado
   `[usdc.approve(escrow), escrow.hold(...)]`** firmado por la wallet del pagador → marca `held`
   (+ `escrow_tx_hash`, `release_after`) + evento WS `{type:"held"}`.
4. **Release → `settled`**, por una de dos vías:
   - **`POST /payments/:id/confirm`** (auth, dueño del comercio) → el `operator` firma
     `escrow.release(...)` → `settled` + `release_tx_hash`.
   - **Auto-release**: un `setInterval` (60s) libera los `held` cuyo `release_after` venció.
5. **`GET /payments/:id`** → fallback; incluye `escrowTxHash`, `releaseTxHash`, `payerEnsName`.

Pagador sin USDC o sin gas → `failed` (`insufficient_funds`). El contrato es
`contracts/VeltEscrow.sol` (`hold`/`release`/`refund`); `operator` = wallet Dynamic subject
`operator`. **Redesplegar el escrow tras cambiar de signer** (el operator cambia de dirección).

## 🏧 Retiro del comercio (`routes/merchants.ts`)

Estado: `pending → processing → settled | failed`. Auth + dueño + `custodial`.

- **`POST /merchants/:id/withdraw { to, amount, private? }`** → `202` + WS. `processWithdrawal`
  (nunca lanza) firma el `transfer` USDC a `to`.
- **`private:true`** → liquida vía **Unlink** (ver abajo) en vez del transfer directo. Sin
  `UNLINK_API_KEY` → `503 unlink_not_configured`. Columna `withdrawals.is_private`.
- `GET /withdrawals/:id` (auth) → fallback.

## 🕵️ Retiro privado vía Unlink (`src/chain/unlink.ts`)

`private:true` enruta el retiro por el **pool shielded de Unlink en arc-testnet**:

1. Rehidrata la wallet Dynamic del comercio como wallet client de viem (`getWalletClient`).
2. Deriva la cuenta Unlink del comercio (`account.fromSeed`, determinista por `merchantId`).
3. **`depositWithApproval`** — USDC reales del comercio entran al pool (Permit2, firmado por su
   wallet Dynamic). **`withdraw`** — retiro privado a `to`, **rompe el link** comercio→destino.

Requiere que la wallet del comercio tenga USDC **+ gas** en arc-testnet.

## 🌐 ENS — subnames ENSv2 (`src/ens/registrar.ts`)

**Fire-and-forget, cosmético, no bloquea nada.** Cada comercio/pagador nuevo obtiene un subname
de `velt.eth` que **resuelve a su wallet**:

- Comercio → `<slug-del-nombre>.velt.eth` · Pagador → `palm-<hash6>.velt.eth`.
- Sistema **ENSv2** en Sepolia: `velt.eth` tiene su propio **registry** + **PermissionedResolver**
  (`ENS_V2_REGISTRY_ADDRESS` / `ENS_V2_RESOLVER_ADDRESS`). El backend hace
  `registry.register(label, ownerEOA, 0, resolver, 0, expiry)` + `resolver.setAddr(node, wallet)`.
- Cola en proceso (la EOA firma todo) + guard in-flight + backfill (registra nombres faltantes al
  consultar comercios o al re-pagar). Si faltan las env → `ensEnabled()=false`, se omite sin error.

## 💳 Funding del pagador vía Blink (`routes/blink.ts`, `routes/fund.ts`)

El pagador recarga USDC con Blink → **aterriza en Base** (chain 8453). **No hay puente a Arc**
(CCTP es stretch); para el demo el pagador se fondea directo en Arc por faucet.

- `GET /fund?personId=` → página HTML que abre el modal de Blink (Custom Tab desde la app),
  resuelve la wallet del pagador y vuelve con deep link `velt://deposit`.
- `POST /blink/sign-payment` → firma ECDSA P-256 del payload (signer de Blink).
- `POST /deposits/record` + `GET /deposits?personId=` (historial). Sin `BLINK_*` → `503`.

## 🖐️ Scan-to-balance (`routes/payers.ts`)

**`GET /payers/:personId/wallet`** (sin auth) → la app escanea la palma → `personId` → devuelve
`{ address, ensName, usdcBalance, transactions[] }`. Alimenta la pantalla "Escanear saldo" y la
info del pagador (saldo antes→después) en el cobro. `404 wallet_not_found` si la palma no tiene wallet.

## 🔌 WebSockets

- `/ws/payments/:id` → `authorizing → held → settled | failed`.
- `/ws/withdrawals/:id` → `processing → settled | failed`.
- Un socket por canal, en memoria, sin broadcast; tras evento terminal cierra. Si nadie escucha,
  el evento se descarta → usar el `GET` de fallback.

## Mapa de endpoints (`/api/v1`)

| Método y ruta | Auth | Hace |
|---|---|---|
| `POST /auth/phone/otp` | no | envía OTP |
| `POST /auth/verify` | no | login-or-create (palma/teléfono) → tokens |
| `POST /auth/link`, `DELETE /auth/identities/:p` | sí | + / − identidad |
| `GET /auth/me`, `DELETE /auth/me` | sí | perfil / borrar cuenta |
| `POST /auth/refresh`, `POST /auth/logout` | refresh | rota / revoca |
| `POST/GET/PATCH/DELETE /merchants[...]` | sí | CRUD de comercios (deriva wallet + ENS) |
| `POST /merchants/:id/withdraw` | sí | retiro (`private?`) → `202` + WS |
| `GET /withdrawals/:id` | sí | estado del retiro |
| `POST /payments/initiate` | no | crea cobro → `201` + `wsUrl` |
| `POST /payments/authorize` | no | claim + escrow hold en background → `202` |
| `POST /payments/:id/confirm` | sí | dueño libera el escrow → `settled` |
| `GET /payments/:id` | no | estado del pago |
| `GET /payers/:personId/wallet` | no | **scan-to-balance** (wallet + historial) |
| `POST /blink/sign-payment`, `GET /deposits/context`, `POST /deposits/record`, `GET /deposits` | no | Blink |
| `GET /fund` (sin prefijo) | no | página de funding |
| `GET /health` (sin prefijo) | no | `{ ok: true }` |

Errores: `{ error, message }`. Códigos: `validation_error`, `auth_failed`, `unsupported_provider`,
`identity_in_use`, `cannot_remove_last_identity`, `must_withdraw_first`, `merchant_not_found`,
`not_account_owner`, `account_not_custodial`, `unlink_not_configured`, `blink_not_configured`,
`payment_not_found`, `withdrawal_not_found`, `wallet_not_found`, `invalid_state`, `internal_error`.

---

## 🧪 Cómo probar (recetas E2E)

> Pre-requisito: `.env` con `SIGNER_BACKEND=dynamic`, `DYNAMIC_*`, `ESCROW_CONTRACT_ADDRESS`
> (del último `npm run deploy:escrow`), `ENS_V2_*`, `UNLINK_API_KEY`, y `schema.sql` aplicado.
> Las wallets EOA necesitan **gas nativo en Arc** (manual). `npm run dev` levanta en :3000.

### Setup de un escenario de pago (crea comercio + wallet de pagador)
```bash
npx tsx scripts/setup-payment-test.ts   # imprime merchantId, wallet del pagador y del operator
```
Fondea la **wallet del pagador** (USDC + gas) y el **operator** (gas) en Arc — las direcciones
las imprime el script. (Ver también [`requests.http`](requests.http) para el flujo HTTP completo.)

### Pago con escrow (palma → held → settled)
```bash
# 1. iniciar cobro
curl -X POST localhost:3000/api/v1/payments/initiate -H 'Content-Type: application/json' \
  -d '{"merchantId":"<MID>","amount":2.5}'      # → paymentId
# 2. (otra terminal) abrir el WS:  wscat -c ws://localhost:3000/ws/payments/<paymentId>
# 3. autorizar con el personId (lo da el bioserver; en el test usa el del setup)
curl -X POST localhost:3000/api/v1/payments/authorize -H 'Content-Type: application/json' \
  -d '{"paymentId":"<PID>","personId":"demo-payer-001"}'   # → 202, luego WS: held
# 4. confirmar entrega (libera el escrow). Necesita Bearer del dueño del comercio.
curl -X POST localhost:3000/api/v1/payments/<PID>/confirm -H "Authorization: Bearer <TOKEN>"
# → WS: settled, con escrowTxHash + releaseTxHash reales. (O espera el auto-release, 5 min.)
```
Verificado en vivo: el USDC se mueve del pagador al comercio, firmado por wallets MPC de Dynamic,
y el pagador obtiene su ENS (`palm-xxxx.velt.eth`).

### Scan-to-balance
```bash
curl localhost:3000/api/v1/payers/demo-payer-001/wallet
# → { address, ensName, usdcBalance, transactions[] }
```

### Retiro privado (Unlink)
```bash
# el comercio necesita USDC + gas en arc-testnet
curl -X POST localhost:3000/api/v1/merchants/<MID>/withdraw -H "Authorization: Bearer <TOKEN>" \
  -H 'Content-Type: application/json' -d '{"to":"0x...","amount":1,"private":true}'
```

### Scripts útiles
- `npm run deploy:escrow` — despliega el escrow con el operator del signer actual.
- `npm run register:ens` — registra el padre `.eth` (one-off; ya hecho para velt.eth en ENSv2).
- `scripts/ens-test-subname.ts` — registra un subname de prueba y verifica que resuelve.

---

## ⚠️ Limitaciones / gotchas

- **Single-instance**: registro de sockets WS, mapa del signer y OTPs pendientes viven en memoria.
  El estado financiero siempre está en la DB.
- **Wallets MPC = EOAs → gas manual en Arc**. Sin gas, el pago/retiro queda `failed`. (Auto-funding
  desde una tesorería es un TODO.)
- **`server_key_shares` en la DB** (hackathon). Producción → vault (HSM/KMS).
- **Cambio de signer ⇒ redeploy del escrow** (el `operator` cambia de dirección).
- **Cambios de esquema ⇒ re-pegar `schema.sql`** en Supabase (idempotente; si no, `PGRST204`).
- **Pagos sin auth** (cualquiera con `merchantId`/`personId`); aceptado en hackathon.
- **No integrado**: puente Blink(Base)→Arc (CCTP), login del pagador, rate limiting, multi-instancia.
