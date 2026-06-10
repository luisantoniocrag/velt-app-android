# Velt Backend — Flujos actuales (v1)

> Documento de contexto para agentes/desarrolladores que llegan al proyecto: qué existe hoy,
> cómo funciona cada flujo y dónde están los límites. La fuente de verdad es el código en
> `src/`; aquí está el mapa. Complementa a [`README.md`](README.md) (setup) e
> [`INTEGRATION-ANDROID.md`](INTEGRATION-ANDROID.md) (contrato hacia la app).

## Qué es este backend

Servicio Fastify (Node 20 + TypeScript strict) que liquida **pagos USDC on-chain en Arc** vía
smart accounts ERC-4337. No hace matching biométrico (eso es el bioserver) ni tiene UI. Recibe
un `personId` ya resuelto (palma identificada) y mueve USDC del pagador al comercio; también
deja al dueño del comercio **retirar** sus fondos, y gestiona **cuentas de usuario** con login
por teléfono (OTP) o palma.

- Persistencia: **Supabase** (PostgREST con service key — salta RLS). Esquema en `src/db/schema.sql`.
- On-chain: **viem + permissionless** detrás de la interfaz `Signer` (`src/chain/signer.ts`).
- Tiempo real: **WebSocket** (`@fastify/websocket`) para el resultado de pagos y retiros.
- Validación: **zod** en cada body y en la config (`src/config.ts` — si falta una env var el
  proceso no arranca).
- Prefijo HTTP: todo cuelga de **`/api/v1`** (salvo `/health` y los WS `/ws/...`).
- Verificación del build: `npm run typecheck`. No hay tests automatizados; E2E manual con
  `requests.http` y `wscat`.

## Glosario — los tres "actores" (no confundirlos)

| Concepto | Tabla | Qué es |
|---|---|---|
| **Usuario** | `users` | La **persona dueña de comercios**. Se loguea (teléfono/palma → JWT) y administra sus comercios. Es el único actor con autenticación. |
| **Comercio** | `merchants` | Un negocio que cobra. Pertenece a un usuario (`owner_user_id`). Tiene una smart account que recibe los pagos. |
| **Pagador** | `velt_users` | La persona que paga con la palma. **No tiene login ni cuenta de usuario** (TODO v2): se identifica solo por `person_id` (el id opaco que devuelve el bioserver) y el backend le deriva una smart account la primera vez que paga. |

Una misma persona física podría ser pagador y usuario, pero hoy son mundos separados:
`velt_users` (pagador) no se relaciona con `users` (dueño).

### Identidades y sesiones del usuario

- `user_identities` `(provider, external_id) → user_id`: liga cualquier credencial a un usuario
  (patrón "accounts"). Providers implementados: `phone` (external_id = teléfono E.164) y `palm`
  (external_id = `personId` del bioserver). `google`/`email` son stubs que lanzan.
  `(provider, external_id)` es único: una palma/teléfono no puede pertenecer a dos usuarios.
  Un usuario puede tener varias identidades (p. ej. teléfono + palma).
- `refresh_tokens`: sesiones del usuario (ver [Tokens](#tokens)).

### Cuentas on-chain: custodial vs externa

Toda smart account (de pagador o de comercio) la deriva el `LocalSigner` de forma determinista:
`ownerPrivKey = keccak256("<LOCAL_SIGNER_MASTER_KEY>:<subjectId>")`, donde `subjectId` es el
`personId` crudo (pagador) o `merchant:<id>` (comercio). La dirección es **contrafactual**
(válida sin desplegar; el primer UserOp incluye el initCode).

- `merchants.custodial = true` → la cuenta la derivó el backend, **tiene la llave** y puede
  firmar retiros. Es lo que pasa al crear un comercio sin `smartAccountAddress`.
- `custodial = false` → el comercio trajo una dirección externa; el backend **no tiene la
  llave**: recibe pagos normalmente pero `withdraw` responde `409 account_not_custodial`.

## Autenticación de usuario (`src/auth/`, `src/routes/auth.ts`)

Provider-agnóstica: interfaz `AuthProvider` (`auth/provider.ts`), elegida por nombre en el body.
`authenticate(credentials, ctx) → { provider, externalId }`. El flujo de alta/login es **uno
solo** (no hay register vs login separados):

### Flujo de login por teléfono (OTP vía Stytch)

1. `POST /auth/phone/otp { phone, channel? }` → normaliza a E.164, dispara el código por
   Stytch (`otps/{whatsapp|sms}/login_or_create`). `channel` default `"whatsapp"` (SMS a México
   requiere allowlist + tarjeta en Stytch; WhatsApp no). Responde `204`.
   - El `phone_id` que devuelve Stytch se guarda en un **Map en memoria con TTL de 10 min**
     (`auth/stytchPhone.ts`). Un redeploy entre enviar y verificar invalida el OTP pendiente.
   - `STYTCH_ENV=test` → sandbox: número `+10000000000`, código `000000`, sin SMS real.
2. `POST /auth/verify { provider: "phone", credentials: { phone, code } }` → verifica el código
   (`otps/authenticate`) y entra al **login-or-create** (abajo).

### Flujo de login por palma

`POST /auth/verify { provider: "palm", credentials: { template } }` → `auth/palmProvider.ts`
manda el template al bioserver (`auth/bioserver.ts`, HTTP firmado con HMAC-SHA256, portado de
`VeltSensorBioService` de Android) y obtiene el `personId` → ese es el `externalId`.

### Login-or-create (`POST /auth/verify`)

Verifica la credencial con el provider y busca `(provider, externalId)` en `user_identities`:

- **Existe** → emite sesión para ese `user_id`. Respuesta: `{ userId, userCreated: false, accessToken, refreshToken, expiresIn }`.
- **No existe** → crea fila en `users` + la identidad, y emite sesión (`userCreated: true`).
  **No crea comercio**: el onboarding del cliente decide eso después (vía `GET /auth/me` →
  `isNew` = no tiene comercios).

Credencial inválida (OTP malo, palma no reconocida) → `401 auth_failed`. Provider desconocido o
stub → `400 unsupported_provider`.

### Gestión de identidades y cuenta

- `POST /auth/link` (auth) — verifica una credencial nueva y la liga al usuario logueado (p. ej.
  añadir la palma a una cuenta creada por teléfono). Si ya pertenece a otra cuenta →
  `409 identity_in_use`; si ya era tuya → `200` idempotente.
- `DELETE /auth/identities/:provider` (auth) — quita una identidad. Quitar la última →
  `409 cannot_remove_last_identity`.
- `GET /auth/me` (auth) — `{ userId, isNew, identities[], merchants[] }`. `isNew` = sin comercios
  activos (lo usa el onboarding de la app).
- `DELETE /auth/me` (auth) — borra la cuenta: **soft-delete** de `users` y de todos sus
  `merchants`, borrado duro de `user_identities`, revocación de todos los `refresh_tokens`.
  Si algún comercio **custodial** tiene saldo USDC > 0 → `409 must_withdraw_first` (la guardia
  no aplica a cuentas externas: el backend no custodia esos fondos).

### Tokens

`auth/tokens.ts` — sin dependencias, `node:crypto`:

- **Access token**: JWT HS256 firmado a mano, `sub = userId`, TTL 15 min
  (`ACCESS_TOKEN_TTL_SECONDS`). La verificación fija `alg` (no confía en el header) y compara la
  firma en tiempo constante.
- **Refresh token**: opaco (32 bytes random), TTL 30 días, guardado **hasheado** (sha256) en
  `refresh_tokens`. `POST /auth/refresh` **rota**: revoca el viejo y emite par nuevo. Reusar un
  refresh ya revocado se trata como robo → **revoca toda la familia** del usuario.
  `POST /auth/logout` revoca el refresh recibido.
- `requireAuth` (`auth/middleware.ts`): preHandler que exige `Authorization: Bearer <access>` y
  deja `request.userId`. Protege el CRUD de comercios, withdraw, `GET /withdrawals/:id` y los
  `/auth/*` de cuenta. **Los endpoints de pago NO requieren auth** (los llama el punto de venta /
  flujo del pagador, que no tiene login en v1).

## Comercios (`src/routes/merchants.ts`)

CRUD autenticado; todo pasa por `loadOwnedMerchant(userId, merchantId)` que exige comercio
activo (`deleted_at is null`, si no `404 merchant_not_found`) y propiedad
(`owner_user_id == request.userId`, si no `403 not_account_owner`).

- `POST /merchants { name, smartAccountAddress? }` → crea el comercio con dueño =
  usuario logueado. Sin dirección → deriva smart account custodial; con dirección externa →
  `custodial=false`.
- `GET /merchants` → lista los comercios activos del usuario.
- `GET /merchants/:id` → detalle + **`usdcBalance` on-chain en vivo** (`getUsdcBalance`,
  formateado con 6 decimales). El balance no se persiste; siempre se lee de la chain.
- `PATCH /merchants/:id { name }` → renombra.
- `DELETE /merchants/:id` → **soft-delete** (`deleted_at`). Si es custodial y tiene saldo > 0 →
  `409 must_withdraw_first`. Soft y no hard porque `payment_requests`/`withdrawals` referencian
  `merchants(id)` sin cascade: un hard-delete rompería FKs y perdería historial financiero.

## Flujo de pago (la pieza central — `src/routes/payments.ts`)

Estado en `payment_requests.status`: `pending → authorizing → settled | failed`. Sin auth.

1. **`POST /payments/initiate { merchantId, amount }`** → valida que el comercio exista y esté
   activo, crea fila `pending`. Responde `201 { paymentId, status, amount, wsUrl }`. La app del
   comerciante abre el WS (`/ws/payments/:id`) **inmediatamente**, antes del authorize, para no
   perder eventos.
2. **`POST /payments/authorize { paymentId, personId }`** → el `personId` viene de identificar
   la palma contra el bioserver (eso ocurre fuera de este backend). Hace un **claim atómico**:
   `UPDATE ... SET status='authorizing' WHERE id=? AND status='pending'` — solo un authorize
   gana la carrera; el perdedor recibe `409 invalid_state`. Responde **`202`** y dispara
   `void settlePayment(...)` en segundo plano.
3. **`settlePayment`** (nunca lanza; cualquier fallo → `failed` + evento WS):
   1. `signer.getOrCreateAccount(personId)` — deriva/crea la smart account del pagador y, de
      paso, calienta el mapa `address→subject` que `signAndSendUserOp` necesita.
   2. `ensureVeltUser` — upsert del pagador en `velt_users` (tolera la carrera de dos pagos
      simultáneos del mismo `personId`); guarda `payer_user_id` en el pago.
   3. Transfiere USDC de la cuenta del pagador a `merchants.smart_account_address`
      (`parseUnits(String(amount), 6)` — PostgREST devuelve `numeric` como string).
   4. Marca `settled` + `tx_hash` (o `failed`) y emite el evento WS.
4. **`GET /payments/:id`** → fallback si el WS no estaba conectado; el estado siempre persiste
   en DB aunque el evento se pierda.

Cuenta del pagador sin fondos → `failed` (saldo insuficiente) **es el comportamiento correcto
de v1**; el funding (Blink) llega en v2.

## Flujo de retiro (`withdrawals` — mismo patrón, al revés)

Estado en `withdrawals.status`: `pending → processing → settled | failed`. Requiere auth +
propiedad del comercio + cuenta **custodial**.

1. **`POST /merchants/:id/withdraw { to, amount }`** → guardas: dueño (`403`), custodial
   (`409 account_not_custodial`). Crea fila `pending`, responde **`202`**
   `{ withdrawalId, status, to, amount, wsUrl }` y dispara `void processWithdrawal(...)`.
2. **`processWithdrawal`** (nunca lanza): marca `processing`, rehidrata la cuenta del comercio
   (`getOrCreateAccount(subjectForMerchant(id))` — firmar "en frío" fallaría), firma el
   `transfer` USDC a `to`, marca `settled` + `tx_hash` o `failed` + `reason`
   (`classifyFailure` traduce el error on-chain a un motivo legible).
3. **`GET /withdrawals/:id`** (auth) → fallback del WS. Si el retiro no es de un comercio tuyo
   responde `404` (no filtra existencia de retiros ajenos).

No hay validación de saldo previa al retiro: si el monto excede el balance, el UserOp falla y
el retiro queda `failed` con su `reason`.

## WebSockets (`src/lib/events.ts`, `src/ws/`)

- Canales: `/ws/payments/:paymentId` y `/ws/withdrawals/:withdrawalId`.
- Eventos de pago: `{type:"authorizing"}` → `{type:"settled", txHash, payerPersonId}` |
  `{type:"failed", reason}`. De retiro: `processing` → `settled`/`failed`.
- **Un solo socket por canal**, registro en memoria, sin broadcast ni persistencia. Tras un
  evento terminal el server cierra el socket. Si nadie está conectado el evento se descarta sin
  error — por eso existen los `GET` de fallback.

## Capa de firma (`src/chain/`)

Interfaz `Signer` elegida por `SIGNER_BACKEND`:

- **`local`** (`localSigner.ts`) — único implementado (Enfoque A, hackathon). El backend
  custodia `LOCAL_SIGNER_MASTER_KEY` y deriva todos los owners de forma determinista.
- **`privy` / `turnkey`** — stubs que lanzan `not implemented`; camino de producción (firma
  externa, el backend nunca toca la llave).

Gotcha: el `LocalSigner` mantiene un **mapa `address→subject` en proceso** que solo se llena al
llamar `getOrCreateAccount`. Por eso `settlePayment` y `processWithdrawal` lo llaman **siempre**
antes de firmar. Cualquier código nuevo que firme debe hacer lo mismo.

## Mapa de endpoints

| Método y ruta (`/api/v1`) | Auth | Hace |
|---|---|---|
| `POST /auth/phone/otp` | no | envía OTP (WhatsApp default / SMS) |
| `POST /auth/verify` | no | login-or-create → tokens (`userCreated`) |
| `POST /auth/link` | sí | liga otra identidad a mi cuenta |
| `DELETE /auth/identities/:provider` | sí | quita una identidad (no la última) |
| `GET /auth/me` | sí | perfil: `isNew`, identidades, comercios |
| `DELETE /auth/me` | sí | borra cuenta (guardia de saldo custodial) |
| `POST /auth/refresh` | no (refresh) | rota refresh → par nuevo |
| `POST /auth/logout` | no (refresh) | revoca el refresh |
| `POST /merchants` | sí | crea comercio (deriva cuenta si no trae) |
| `GET /merchants` | sí | lista mis comercios |
| `GET /merchants/:id` | sí | detalle + `usdcBalance` on-chain |
| `PATCH /merchants/:id` | sí | renombra |
| `DELETE /merchants/:id` | sí | soft-delete (guardia de saldo) |
| `POST /merchants/:id/withdraw` | sí | retiro → `202` + WS |
| `GET /withdrawals/:id` | sí | estado del retiro (fallback WS) |
| `POST /payments/initiate` | no | crea cobro → `201` + `wsUrl` |
| `POST /payments/authorize` | no | claim + liquidación en background → `202` |
| `GET /payments/:id` | no | estado del pago (fallback WS) |
| `GET /health` (sin prefijo) | no | `{ ok: true }` |

Errores: siempre `{ error: <code>, message }`. Códigos en uso: `validation_error`,
`invalid_phone`, `unsupported_provider`, `auth_failed`, `invalid_refresh_token`,
`identity_in_use`, `identity_not_found`, `cannot_remove_last_identity`, `must_withdraw_first`,
`merchant_not_found`, `not_account_owner`, `account_not_custodial`, `payment_not_found`,
`withdrawal_not_found`, `invalid_state`, `internal_error`.

## Limitaciones conocidas (v1)

**Single-instance por diseño.** Tres piezas viven en memoria del proceso: el registro de
sockets WS, el mapa `address→subject` del `LocalSigner` y los OTP pendientes de Stytch. Escalar
horizontalmente o un redeploy a media operación rompe esas tres cosas (el estado financiero
nunca se pierde: siempre está en la DB).

**Seguridad / producción pendiente:**
- El signer `local` custodia la master key → todas las llaves derivan de un secreto en env.
  Producción = `privy`/`turnkey` (stubs).
- Sin rate limiting en ningún endpoint (incluido el envío de OTP).
- Los endpoints de pago no tienen auth: cualquiera con un `merchantId` puede iniciar cobros y
  cualquiera con un `paymentId` + `personId` puede autorizar. Aceptado en v1 (el `personId`
  real solo lo produce el bioserver).
- Supabase con service key (salta RLS); la autorización es 100% lógica de aplicación.

**Alcance que NO está en v1:** login del pagador (en pagos no hay cuenta de usuario), providers
`google`/`email` (stubs), funding de cuentas (Blink), ENS, session keys, multi-chain, QR,
apps iOS/Watch. Marcado `// TODO v2` donde aplica.

**Operativos:**
- Cambios de esquema → re-pegar `src/db/schema.sql` en el SQL editor de Supabase (es
  idempotente); si no, PostgREST responde `PGRST204` por cache de esquema.
- `amount` llega de PostgREST como string → siempre `parseUnits(String(amount), 6)`.
- `STYTCH_ENV=test` no manda SMS reales; `live` requiere plan de pago.
