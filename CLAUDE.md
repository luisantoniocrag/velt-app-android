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
  smart account ERC-4337 y ejecuta una transferencia USDC en Arc. Stack: Node 20 + TypeScript +
  Fastify + Supabase + viem/permissionless. Ver [`backend/README.md`](backend/README.md). El build
  se valida con `cd backend && npm run typecheck`.

> Estado actual de la app: la **app principal está en construcción**. Lo implementado hoy son las
> herramientas del sensor, accesibles desde un **menú de Configuración**.

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

## Notas / gotchas importantes

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
