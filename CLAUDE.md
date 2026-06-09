# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Velt — Monorepo

Monorepo del proyecto Velt: validación biométrica de **palma** con el sensor **Velt**.

```
.
├── android-app/   # app Android (Kotlin + Jetpack Compose) — proyecto Gradle
└── backend/       # backend Node/TypeScript (pagos USDC en Arc) — v1 core
```

- **`android-app/`** — la app se conecta al sensor por Bluetooth, captura el template biométrico
  y lo verifica contra el bioserver. Abre Android Studio apuntando a esta carpeta (ahí vive el
  `settings.gradle.kts`).
- **`backend/`** — "cerebro" de pagos: recibe un `personId` (palma identificada), lo mapea a una
  smart account ERC-4337 y ejecuta una transferencia USDC en Arc; también deriva la cuenta del
  comerciante y le permite **retirar** sus fondos. Stack: Node 20 + TypeScript + Fastify + Supabase +
  viem/permissionless. Ver [`backend/README.md`](backend/README.md). El build se valida con
  `cd backend && npm run typecheck`.

> Estado actual de la app: la **app principal está en construcción**. Lo implementado hoy son las
> herramientas del sensor, accesibles desde un **menú de Configuración**.

## Convenciones de código

- **Código autoexplicable, sin comentarios.** Prioriza nombres claros y buena estructura en lugar de
  comentarios. Solo añade comentarios cuando la lógica sea genuinamente compleja, y en ese caso
  explica el *por qué*, no el *qué*.

---

## Build & Run

Todos los comandos Gradle se ejecutan desde `android-app/`:

```bash
cd android-app
./gradlew :app:assembleDebug        # compilar APK debug
./gradlew :app:installDebug         # instalar en dispositivo/emulador conectado
./gradlew :app:compileDebugKotlin   # solo compilar Kotlin (verificación rápida)
```

- `minSdk = 29`, `targetSdk = 36`, `compileSdk = 36`, namespace `com.velt`.
- Tras cambios de **permisos en el manifest**, reinstalar limpio:
  `adb uninstall com.velt && ./gradlew :app:installDebug` (desde `android-app/`).

### Diagnóstico (logcat)

```bash
adb logcat -s VeltSensorClient
```

Etiquetas de log relevantes: `VeltSensorClient`, `VeltSensorRepository`, `VeltSensorBioService`.

---

## Estructura

Código fuente en `android-app/app/src/main/java/com/velt/`:

```
com.velt/
├── MainActivity.kt              # navegación entre pantallas (enum Screen)
├── ui/
│   ├── Screens.kt               # HomeScreen, ConfigMenuScreen, PalmValidationScreen
│   ├── BluetoothScreen.kt       # emparejar/listar dispositivos del sensor
│   └── theme/                   # tema Compose
└── sensor/                      # capa de comunicación con el sensor Velt
    ├── VeltSensorClient.kt      # cliente BLE (wake) + SPP (RFCOMM), parser de eventos
    ├── VeltSensorRepository.kt  # orquesta la sesión (wake → SPP → capture)
    ├── VeltSensorBioService.kt  # HTTP al bioserver (HMAC-SHA256), verifyUser()
    └── VeltSensorConfig.kt      # credenciales, endpoint y timeouts
```

### Navegación

`HomeScreen` (placeholder app principal) → **Configuración** → `ConfigMenuScreen`, que ofrece:
- **Dispositivos Bluetooth** → `BluetoothScreen`
- **Validar palma** → `PalmValidationScreen`

Los permisos Bluetooth se solicitan al navegar (ver `navigateWithBtPermissions` en `MainActivity`).

---

## Flujo de validación de palma

Implementado en `VeltSensorRepository.startSession()` + `PalmValidationScreen`:

1. **Wake BLE** — `connectBleAndWake()` conecta por GATT (`TRANSPORT_LE`) y escribe `0x01` en la
   característica del servicio `0x1815`. **Esto es imprescindible**: sin el wake, el subsistema de
   captura del sensor queda dormido — responde los comandos SPP con ACK pero **nunca enciende la
   cámara ni emite eventos de posición/captura**. Es *best-effort* y vuelca el GATT real al log
   para diagnóstico.
2. **Conexión SPP (RFCOMM)** — `connectClassicAndStartReader()` (UUID `00001101-...`), con
   reintentos. Arranca un lector continuo del socket.
3. **`{"cmd":"capture"}`** — pone el sensor en modo captura.
4. **`{"cmd":"setcolor",...}`** — LED blanco parpadeante (indicador "listo para escanear").
5. La UI **se suscribe a los eventos ANTES** de iniciar la sesión y espera el evento `capture`
   (timeout `CAPTURE_TIMEOUT_MS = 45s`). Coloca la palma → llegan eventos `position` (posición de
   la mano en vivo) y finalmente `capture` con el template.
6. **Verificación** — `VeltSensorBioService.verifyUser(template)` hace `POST /api/subject/identify`
   firmado con HMAC-SHA256. Se muestra el HTTP status y la respuesta.

### Protocolo SPP (JSON terminado en `\r`)

**Comandos (app → sensor):** `{"cmd":"capture"}`, `{"cmd":"idle"}`, `{"cmd":"sleep"}`,
`{"cmd":"setcolor","color":<rgbInt>,"blink_en":0|1,"blink_on":<ms>,"blink_off":<ms>}`.

**Eventos (sensor → app):**
- Saludo en texto plano al conectar (no-JSON, se ignora).
- **Eco** del comando enviado (`{"cmd":...}`) → se ignora.
- `{"event":"<cmd>","state":"ok"}` → ACK.
- `{"event":"position","STATE":<0-6>}` → posición de la mano (ver `HandPositionStatus`).
- `{"event":"capture","userdata":"<base64>"}` → template biométrico (evento final).

El parser (`VeltSensorClient.processCompleteJsons`) acumula bytes y extrae objetos JSON por llaves
balanceadas (maneja JSONs grandes y concatenados).

---

## Pantalla Bluetooth

`BluetoothScreen` lista emparejados, descubre y empareja nuevos, y selecciona el dispositivo SPP.
**Solo muestra dispositivos del sensor**, filtrados por `DEVICE_NAME_PREFIX` (el prefijo con el que
el hardware se anuncia por Bluetooth — ver constante en el código; es un nombre de fábrica del
hardware, no parte del branding de la app).

---

## Backend — pagos USDC en Arc

> Detalle completo en [`backend/README.md`](backend/README.md). Esto es el mapa de arquitectura.

Servicio Fastify (sin UI, sin matching biométrico) que recibe un `personId` ya resuelto por el
bioserver y liquida un pago USDC on-chain. Stack: **Node 20 + TypeScript (strict) + Fastify +
`@fastify/websocket` + Supabase (PostgREST) + viem/permissionless (ERC-4337) + zod**.

### Comandos (desde `backend/`)

```bash
npm run dev         # tsx watch — arranca en http://localhost:3000
npm run typecheck   # tsc --noEmit — verificación rápida (la fuente de verdad del build)
npm run build       # tsc -p tsconfig.json → dist/
npm start           # node dist/index.js (producción)
```

- No hay framework de tests todavía. La verificación E2E es manual: [`backend/requests.http`](backend/requests.http)
  para el HTTP y `wscat -c ws://localhost:3000/ws/payments/<paymentId>` (o `.../ws/withdrawals/<id>`)
  para el WS.
- Config validada con zod al arranque (`src/config.ts`): si falta una env var, el proceso **falla
  ruidosamente**. Copiar `.env.example` → `.env`. Las tablas se crean pegando `src/db/schema.sql`
  en el editor SQL de Supabase.

### Ciclo de vida de un pago (la pieza central)

El estado vive en `payment_requests.status`: `pending → authorizing → settled | failed`.

1. `POST /api/v1/payments/initiate` (`merchantId`, `amount`) → crea fila `pending`, devuelve
   `paymentId` + `wsUrl`. La app del comerciante abre el WS **inmediatamente** (antes de authorize).
2. `POST /api/v1/payments/authorize` (`paymentId`, `personId`) → hace un **claim atómico**
   (`UPDATE ... WHERE status='pending'`): solo un authorize gana la carrera. Responde **`202` y
   liquida en segundo plano** (`void settlePayment(...)`) — la confirmación on-chain es asíncrona.
3. `settlePayment` (en `routes/payments.ts`) nunca lanza: deriva/crea la smart account del pagador,
   asegura `velt_users`, resuelve la cuenta del comerciante, transfiere USDC y marca `settled`
   (con `tx_hash`) o `failed`. Cada transición emite un evento WS.
4. `GET /api/v1/payments/:id` es el **fallback** si el WS no estaba conectado — el estado siempre
   persiste en la DB aunque el evento se pierda.

### Retiro de fondos del comerciante (`withdrawals`)

Mismo patrón que un pago, pero al revés (saca USDC de la cuenta del comerciante). El estado vive en
`withdrawals.status`: `pending → processing → settled | failed`.

1. `POST /api/v1/merchants/:id/withdraw` (`to`, `amount`) → crea fila `pending`, responde **`202`**
   con `withdrawalId` + `wsUrl` y liquida en segundo plano (`void processWithdrawal(...)`).
2. `processWithdrawal` (en `routes/merchants.ts`) nunca lanza: rehidrata la cuenta del comerciante
   (`getOrCreateAccount(subjectForMerchant(id))`), firma un `transfer` USDC a `to` y marca `settled`
   (con `tx_hash`) o `failed` (con `reason`). Cada transición emite un evento WS (`/ws/withdrawals/:id`).
3. `GET /api/v1/withdrawals/:id` es el **fallback** del WS.

La smart account del comerciante se **deriva igual que la del pagador** (es contrafactual): se crea
al crear el comercio (`POST /merchants` sin `smartAccountAddress`) y queda marcada `custodial=true`.
Si en cambio se trae una dirección **externa**, se respeta pero queda `custodial=false`: el backend **no
tiene su llave**, así que `withdraw` la rechaza con `409 account_not_custodial`. Solo las cuentas
derivadas (custodiadas por el backend) pueden retirar. El `withdraw` además exige que quien llama sea el
**dueño** del comercio (`merchant.owner_user_id == request.userId`, si no `403 not_account_owner`).

### Cuentas de usuario y comercios (`users` → `merchants`)

El **usuario** (`users`) es la persona dueña de **N comercios**. Tiene una o varias **identidades** de
login (`user_identities`: teléfono, palma, ...) y administra sus comercios (`merchants.owner_user_id`)
con CRUD autenticado en `routes/merchants.ts`: `POST /merchants` (crea, dueño = `request.userId`),
`GET /merchants` (lista), `GET /merchants/:id` (+ `usdcBalance` on-chain vía `getUsdcBalance` de
`chain/usdc.ts`), `PATCH /merchants/:id` (renombra), `DELETE /merchants/:id`. El helper
`loadOwnedMerchant(userId, merchantId)` carga el comercio activo y exige propiedad (`404`/`403`); lo
usan get/patch/delete y withdraw.

**Borrado = soft-delete** (`users.deleted_at`, `merchants.deleted_at`): `payment_requests`/`withdrawals`
referencian `merchants(id)` sin cascade, así que un hard-delete rompería FK y perdería historial. Las
identidades y `refresh_tokens` **sí** se borran/revocan en duro. `DELETE /merchants/:id` y `DELETE /auth/me`
**bloquean con `409 must_withdraw_first`** si una cuenta **custodial** tiene saldo USDC `> 0` (la guardia
no aplica a externas: el backend no custodia esos fondos). No confundir `users` con `velt_users` (pagador).

### Autenticación de usuario (`src/auth/`) — palma/teléfono → JWT, provider-agnóstica

Solo el dueño (los pagos siguen sin login del pagador). Mismo patrón que `Signer`: interfaz
`AuthProvider` (`auth/provider.ts`) elegida por nombre. Implementados: `palm` (`auth/palmProvider.ts`
→ `auth/bioserver.ts`, HMAC-SHA256 portado de `VeltSensorBioService`) y `phone`
(`auth/phoneProvider.ts` → `auth/stytchPhone.ts`, OTP por SMS/WhatsApp vía **Stytch**);
`google`/`email` son stubs. `AuthProvider.authenticate(credentials, ctx) → { provider, externalId }`;
palma → `externalId = personId`, teléfono → `externalId = número E.164`.

**Login por teléfono** es de dos pasos: `POST /auth/phone/otp { phone, channel? }` dispara el código
(Stytch `otps/{sms|whatsapp}/login_or_create`), y luego `register`/`login` con `provider:"phone",
credentials:{ phone, code }` verifica (`otps/authenticate`). `channel` = `"sms"` (default) o `"whatsapp"`.
Requiere `STYTCH_PROJECT_ID`/`STYTCH_SECRET` (opcionales en `config.ts`; el provider lanza si faltan) y
`STYTCH_ENV` (`test` = números sandbox `+10000000000`/código `000000` sin SMS real; `live` = SMS real,
plan de pago). `auth/stytchPhone.ts` llama a la API REST de Stytch (Basic Auth) y guarda el `phone_id`
(method_id) en un **Map en memoria con TTL** entre enviar y verificar, para que el contrato hacia la app
no cambie (`{phone}` → `{phone, code}`). Single-instance, como el registro de sockets: un redeploy entre
ambos pasos invalida el OTP pendiente.

La tabla **`user_identities` `(provider, external_id)` → `user_id`** liga cualquier identidad de
cualquier proveedor a un usuario (patrón "accounts"): añadir Google = nuevo provider + filas, sin tocar
login/register. `POST /auth/link` añade una 2ª identidad (p. ej. la palma) a la cuenta logueada;
`DELETE /auth/identities/:provider` la quita (no la última → `409`).

**Tokens** (`auth/tokens.ts`): access JWT HS256 corto (15m, **firmado a mano con `node:crypto`**,
`alg` fijo, compare en tiempo constante, `sub = userId`) + refresh opaco (30d) guardado **hasheado**
(`sha256`) en `refresh_tokens`, **rotativo y revocable**; reuso de un refresh revocado → revoca toda la familia.

Endpoints `/api/v1/auth/*` (`routes/auth.ts`): `phone/otp` (envía el código), `register` (self-signup:
verifica palma/teléfono → crea **usuario** + identidad → emite sesión; **no** crea comercio), `login`,
`link`, `DELETE identities/:provider`, `GET me`, `DELETE me`, `refresh`, `logout`.
`requireAuth` (`auth/middleware.ts`) es el preHandler que exige `Authorization: Bearer` y deja
`request.userId`. Protege el CRUD de comercios, `withdraw`, `GET /withdrawals/:id` y los `/auth/*` de cuenta.

### Capa de firma — interfaz `Signer` (`src/chain/signer.ts`)

Todo el on-chain está detrás de `Signer` y se elige por `SIGNER_BACKEND`; el resto del backend no
cambia al migrar entre enfoques:

- **`local`** (`localSigner.ts`) — **único implementado** (Enfoque A, hackathon). El backend custodia
  `LOCAL_SIGNER_MASTER_KEY`; el owner de cada smart account se deriva determinista:
  `ownerPrivKey = keccak256("<masterKey>:<subjectId>")` (el `subjectId` es el `personId` crudo de un
  pagador o `merchant:<id>` de un comerciante). La dirección es **contrafactual** (válida sin desplegar);
  el primer UserOp incluye el initCode que despliega la cuenta.
- **`privy` / `turnkey`** — stubs (`throw "not implemented"`), el camino de producción (firma externa,
  el backend nunca toca la llave). Notas de implementación dentro de cada archivo.

### Notas / gotchas del backend

- **`202` + liquidación en background**: `authorize` responde antes de que el pago termine. El
  resultado real (`settled`/`failed` + `txHash`) llega **solo por WebSocket** o por `GET /payments/:id`.
- **Registro de sockets en memoria** (`lib/events.ts`): un solo socket por canal (`payment:<id>` o
  `withdrawal:<id>`), sin broadcast, sin persistencia. Se cierra tras un evento terminal. Sirve para una
  instancia; escalar a varias requiere un bus externo.
- **Mapa `address→subject` en proceso** (`LocalSigner`): `signAndSendUserOp` re-deriva el owner desde
  ese mapa, que solo se llena al llamar `getOrCreateAccount`. Funciona porque `settlePayment` y
  `processWithdrawal` **siempre** llaman `getOrCreateAccount` antes de firmar (mapa caliente). Un firmar
  "en frío" por dirección fallaría.
- **`amount` numeric**: PostgREST suele devolver `numeric(18,6)` como **string**; se convierte con
  `parseUnits(String(amount), 6)` (`USDC_DECIMALS`). No asumir `number`.
- **Cambios de esquema → re-aplicar `schema.sql` en Supabase**: `schema.sql` es idempotente
  (`create table if not exists`, `alter table ... add column if not exists`). Tras añadir una columna
  o tabla hay que **volver a pegarlo** en el SQL editor; si no, PostgREST responde `PGRST204` ("could
  not find the column ... in the schema cache"). El script termina con `notify pgrst, 'reload schema'`
  para refrescar el cache sin esperar.
- **Cuenta sin fondos → `failed` (saldo insuficiente)** es el comportamiento correcto de v1; el funding
  (Blink) llega en v2.
- **Supabase con service key** (`db/client.ts`): salta RLS. Se le inyecta el `WebSocket` de `ws` porque
  supabase-js construye su RealtimeClient siempre y Node <22 no trae WS nativo (aunque no usamos realtime).
- **Alcance v1 = solo core**: NO incluye ENS, Blink, session keys, multi-chain, QR, apps
  iOS/Watch ni rate limiting. La **cuenta de usuario** (teléfono/palma → JWT, CRUD de comercios,
  link/unlink de identidades, borrado con guardia de saldo) ya está; falta login del **pagador** y
  proveedores extra (Google/email son stubs). Marcado como `// TODO v2` donde aplica.
- **`npm run dev` revienta con `TransformError: The service was stopped` / "installed esbuild for
  another platform"**: `tsx` usa el binario nativo de esbuild y a veces `node_modules/@esbuild/<plat>/bin/`
  queda vacío (al copiar `node_modules` o cambiar de versión de Node). Arreglo:
  `rm -rf node_modules/esbuild node_modules/@esbuild && npm install` (o `rm -rf node_modules && npm install`).

---

## Notas / gotchas importantes (app Android)

- **`registerReceiver` en Android 13+**: debe declarar exportación. Se usa
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. Omitirlo lanza `SecurityException`
  y rompía el descubrimiento Bluetooth.
- **`BLUETOOTH_SCAN` con `neverForLocation`**: sin esa bandera, `startDiscovery()` en Android 12+
  exige permiso de ubicación en runtime (que no se solicita) y no devuelve resultados.
- **`SharedFlow` con `replay = 0`**: hay que suscribirse a `repo.events` **antes** de `startSession()`,
  o se pierden eventos tempranos (incluido el `capture`). Ver `PalmValidationScreen`.
- **Wake BLE obligatorio para el sensor real**: ver flujo arriba. (Una implementación de referencia
  probada contra un mock en laptop no lo necesitaba; el hardware real sí.)
- **Escritura GATT**: en Android 13+ se usa `writeCharacteristic(ch, value, type)`; en versiones
  anteriores, la API antigua. Ver `VeltSensorClient.writeWake`.
- **`VeltSensorConfig`** contiene `CLIENT_ID`/`SHARED_SECRET` del bioserver y un cliente HTTP que
  **acepta cualquier certificado TLS** (`buildUnsafeClient`) — solo apto para desarrollo/sandbox.

---

## Pendientes

- Construir la app principal (hoy `HomeScreen` es placeholder).
- Endurecer TLS y mover credenciales fuera del código antes de producción.
