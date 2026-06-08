# Velt Backend (v1, core)

El "cerebro" de Velt: recibe un `personId` resuelto por el bioserver, lo mapea a una
**smart account** (ERC-4337) en la blockchain **Arc** (Circle) y ejecuta una transferencia
de **USDC** al comerciante. Sin UI, sin matching biométrico (eso lo hace el bioserver externo).

## Stack

Node 20 · TypeScript (strict) · **Fastify** · `@fastify/websocket` · **Supabase** (PostgreSQL) ·
**viem** + **permissionless** (ERC-4337) · **zod**.

## Puesta en marcha

```bash
cd backend
npm install
cp .env.example .env          # rellenar credenciales (ver abajo)
# Crear las tablas en Supabase: pegar src/db/schema.sql en el SQL editor
npm run dev                   # arranca en http://localhost:3000
```

Comandos: `npm run dev` (watch), `npm run build` + `npm start` (producción), `npm run typecheck`.

## Variables de entorno

Ver [`.env.example`](.env.example). Resumen:

| Var | Para qué |
|---|---|
| `PORT` | puerto HTTP (default 3000) |
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | acceso a la base de datos |
| `SUPABASE_ANON_KEY` | opcional — solo para el login por teléfono (Supabase Phone Auth / OTP) |
| `ARC_RPC_URL`, `ARC_CHAIN_ID` | red Arc |
| `USDC_CONTRACT_ADDRESS` | contrato USDC en Arc |
| `ERC4337_BUNDLER_URL`, `ERC4337_ENTRYPOINT_ADDRESS` | infraestructura ERC-4337 (EntryPoint v0.7) |
| `SIGNER_BACKEND` | `local` \| `privy` \| `turnkey` |
| `LOCAL_SIGNER_MASTER_KEY` | clave maestra (solo `local`) |
| `JWT_SECRET` | firma de los access JWT (≥32 chars) |
| `ACCESS_TOKEN_TTL_SECONDS`, `REFRESH_TOKEN_TTL_SECONDS` | vida de los tokens (defaults 900 / 2592000) |
| `BIOSERVER_URL`, `BIOSERVER_CLIENT_ID`, `BIOSERVER_SHARED_SECRET` | bioserver (proveedor de auth por palma) |

La config se valida con zod al arranque (`src/config.ts`): si falta algo, el proceso falla
ruidosamente. Nunca se loguean secretos.

## API

Base: `/api/v1`.

| Método | Ruta | Auth | Descripción |
|---|---|---|---|
| `POST` | `/auth/phone/otp` | — | login por teléfono (paso 1): envía el código por SMS/WhatsApp vía Supabase Phone Auth |
| `POST` | `/auth/register` | — | self-signup: verifica la identidad (palma → bioserver, o teléfono → OTP), **crea el usuario**, lo liga y devuelve `accessToken`+`refreshToken`. NO crea comercios |
| `POST` | `/auth/login` | — | verifica la identidad y, si está ligada a un usuario, emite sesión |
| `POST` | `/auth/link` | **Bearer** | añade una identidad (p. ej. la palma) al usuario autenticado (`409 identity_in_use` si ya es de otra cuenta) |
| `DELETE` | `/auth/identities/:provider` | **Bearer** | quita una identidad; `409 cannot_remove_last_identity` si es la única |
| `GET` | `/auth/me` | **Bearer** | perfil: `{ userId, isNew, identities, merchants }` |
| `DELETE` | `/auth/me` | **Bearer** | elimina la cuenta (soft-delete); `409 must_withdraw_first` si algún comercio custodial tiene saldo |
| `POST` | `/auth/refresh` | — | rota el `refreshToken` → nuevo par (revoca el viejo) |
| `POST` | `/auth/logout` | — | revoca el `refreshToken` (`204`) |
| `POST` | `/merchants` | **Bearer** | crea un comercio del usuario; el backend **deriva su smart account** (ERC-4337, `custodial=true`). `smartAccountAddress` opcional → cuenta **externa** (`custodial=false`, no retira) |
| `GET` | `/merchants` | **Bearer** | lista los comercios activos del usuario |
| `GET` | `/merchants/:id` | **Bearer** | un comercio del usuario + `usdcBalance` on-chain |
| `PATCH` | `/merchants/:id` | **Bearer** | renombra un comercio del usuario |
| `DELETE` | `/merchants/:id` | **Bearer** | elimina (soft-delete) un comercio; `409 must_withdraw_first` si custodial con saldo |
| `POST` | `/payments/initiate` | — | crea un cobro (`pending`) → devuelve `paymentId` + `wsUrl` |
| `POST` | `/payments/authorize` | — | recibe `personId`, responde `202`, liquida on-chain en segundo plano |
| `GET` | `/payments/:id` | — | estado del pago (fallback del WS) |
| `POST` | `/merchants/:id/withdraw` | **Bearer** | retira USDC a `to`; responde `202` → `withdrawalId` + `wsUrl`. Solo el **dueño** del comercio (si no `403`) y **solo cuentas `custodial`** (externa → `409`) |
| `GET` | `/withdrawals/:id` | **Bearer** | estado del retiro (fallback del WS); solo del dueño |

WebSocket:
- `GET /ws/payments/:paymentId` — eventos: `authorizing` → `settled` (con `txHash`) o `failed` (con `reason`).
- `GET /ws/withdrawals/:withdrawalId` — eventos: `processing` → `settled` (con `txHash`) o `failed` (con `reason`).

El socket se cierra tras el evento terminal.

Colección de prueba de punta a punta: [`requests.http`](requests.http).
Para el WebSocket: `wscat -c ws://localhost:3000/ws/payments/<paymentId>` (abrir **antes** de `authorize`).

## Cuentas de usuario y autenticación

El **usuario** (`users`) es la persona dueña de **N comercios**. Tiene una o varias **identidades**
de login (`user_identities`: teléfono, palma, ...) y administra sus comercios (`merchants.owner_user_id`)
vía CRUD autenticado. `register` crea el **usuario** (no un comercio); los comercios se crean luego con
`POST /merchants`. `GET /auth/me` indica si es nuevo y qué comercios/identidades tiene; `DELETE /auth/me`
y `DELETE /merchants/:id` hacen **soft-delete** y **bloquean con `409 must_withdraw_first`** si una
cuenta custodial tiene saldo USDC on-chain.

> No confundir con `velt_users`, que es el mapeo del **pagador** (`personId` → smart account) y no tiene login.

Provider-agnóstica, mismo patrón que `Signer`: la interfaz `AuthProvider` (`src/auth/provider.ts`)
se elige por nombre y traduce unas credenciales en una identidad estable.

```ts
AuthProvider.authenticate(credentials, ctx) -> { provider, externalId }
   palm   → externalId = personId (verificado contra el bioserver, HMAC-SHA256)
   phone  → externalId = teléfono E.164 (verificado por OTP contra Supabase Phone Auth)
   google → externalId = sub      (stub, futuro)
```

**Login por teléfono (Supabase Phone Auth / OTP).** Dos pasos: `POST /auth/phone/otp { phone, channel? }`
envía un código (vía Supabase), y luego `POST /auth/register|login { provider:"phone",
credentials:{ phone, code } }` lo verifica y emite sesión. `channel` puede ser `"sms"` (default,
cualquier proveedor) o `"whatsapp"` (**solo con Twilio** como proveedor y un sender de WhatsApp
aprobado). Requiere `SUPABASE_ANON_KEY` en el `.env` y un **proveedor** configurado en Supabase
(Dashboard → Authentication → Providers → Phone: Twilio, Vonage o MessageBird — Supabase no envía
mensajes por sí solo). El teléfono se normaliza a E.164 (`+5215512345678`) y se liga al usuario
en `user_identities` como cualquier otra identidad.

La tabla `user_identities (provider, external_id) → user_id` liga cualquier identidad de
cualquier proveedor a un usuario (patrón "accounts"). Añadir Google mañana = un nuevo
`AuthProvider` + filas en esa tabla; el resto no cambia. `POST /auth/link` añade una 2ª identidad
(p. ej. la palma) a la cuenta ya logueada.

**Tokens** (`src/auth/tokens.ts`): **access JWT HS256 corto** (15m, stateless, firmado a mano con
`node:crypto`) + **refresh token opaco** (30d) guardado **hasheado** (`sha256`) en `refresh_tokens`,
**rotativo y revocable**. `refresh` revoca el viejo y emite uno nuevo (reuso de un refresh revocado →
revoca toda la familia: detección de robo). `logout` revoca. El access se valida con `alg` fijo HS256
(no se confía en el `alg` del token) y comparación en tiempo constante.

**Flujo palma**: `POST /auth/register { name, provider:"palm", credentials:{ template } }` →
el backend llama al bioserver `identify` → `personId` → crea+liga el comerciante → emite sesión.
Login posterior: `POST /auth/login { provider:"palm", credentials:{ template } }`.

Endpoints protegidos: usar `Authorization: Bearer <accessToken>` (preHandler `requireMerchantAuth`,
`src/auth/middleware.ts`, deja `request.merchantId`).

## Firma de transacciones — Enfoque A vs B

La capa de firma está detrás de la interfaz `Signer` (`src/chain/signer.ts`) y se elige con
`SIGNER_BACKEND`, así que se puede cambiar sin tocar el resto del código.

```ts
interface Signer {
  getOrCreateAccount(subjectId: string): Promise<{ address: string }>;
  signAndSendUserOp(p: { from: string; to: string; amountUsdc: bigint }): Promise<{ txHash: string }>;
}
```

El `subjectId` es la entrada de la derivación determinista. Un **pagador** usa su `personId` crudo;
un **comerciante** usa `merchant:<merchantId>` (namespacing para que jamás colisione con un `personId`).
Así la misma capa de firma da cuentas tanto a pagadores como a comerciantes.

### Enfoque A — Custodia en el backend (`SIGNER_BACKEND=local`) — **implementado**

`src/chain/localSigner.ts`. El backend custodia `LOCAL_SIGNER_MASTER_KEY` y firma las
UserOperations directamente con viem/permissionless. El owner de cada smart account se deriva
de forma **determinista**:

```
ownerPrivKey = keccak256("<masterKey>:<subjectId>")
```

así la dirección de la cuenta es estable y reproducible sin almacenar una llave por usuario
ni por comerciante.

- **Pros:** rápido de montar, cero dependencias externas. Ideal para el hackathon.
- **Contras:** el backend custodia llaves; si se filtra el entorno, se comprometen todas las cuentas.

### Enfoque B — Signer externo (`SIGNER_BACKEND=privy` | `turnkey`) — **stub (camino de producción)**

`src/chain/privySigner.ts`, `src/chain/turnkeySigner.ts`. Delegan custodia y firma a Privy o
Turnkey: el backend pide firmas vía SDK y **nunca toca la llave privada**. Hoy son stubs
(`throw new Error("not implemented")`) con notas de cómo completarlos en cada archivo.

- **Pros:** no custodias llaves; apto para producción.
- **Contras:** más setup y una dependencia externa.

Para migrar A → B basta implementar la clase stub y cambiar `SIGNER_BACKEND`; el resto del
backend no cambia.

## Notas de alcance (v1)

Esta versión es **solo el core**. NO incluye (vienen en specs posteriores): ENS, Blink, Unlink,
session keys/agentes, multi-chain, QR, apps iOS/Watch, rate limiting. El **login de comerciante**
(palma → JWT) ya está; falta login del pagador y proveedores extra (Google/email son stubs).
Donde aplica, marcado como `// TODO v2` en el código.

Las smart accounts pueden empezar **sin fondos**: en ese caso el pago se marca `failed`
(saldo insuficiente), que es el comportamiento correcto para v1 — el funding llega con Blink en v2.
