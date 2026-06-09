# Integración del backend en la app Android (desde cero)

Guía práctica para consumir el backend de Velt desde la app. Cubre auth (teléfono/palma),
cuentas de usuario, comercios (CRUD), cobros y retiros.

## URLs

| Entorno | HTTP base | WebSocket base |
|---|---|---|
| **Producción** | `https://velt-app-android-production.up.railway.app` | `wss://velt-app-android-production.up.railway.app` |
| Local | `http://10.0.2.2:3000` (emulador → host) | `ws://10.0.2.2:3000` |

- Todos los endpoints REST cuelgan de **`/api/v1`** (p. ej. `…/api/v1/auth/verify`).
- Los WebSocket cuelgan de **`/ws`** (sin `/api/v1`).
- Health check: `GET https://…/health` → `{ "ok": true }`.

> **Antes de empezar, confirma que producción está lista.** Dos chequeos:
> ```
> # 1) build al día → debe dar 401 (no 404)
> curl -o /dev/null -w "%{http_code}\n" https://velt-app-android-production.up.railway.app/api/v1/merchants
> # 2) OTP configurado → debe dar 204 (no 500)
> curl -o /dev/null -w "%{http_code}\n" -X POST \
>   https://velt-app-android-production.up.railway.app/api/v1/auth/phone/otp \
>   -H "Content-Type: application/json" -d '{"phone":"+10000000000"}'
> ```
> - **404** en (1) → el deploy está viejo: haz push y deja que Railway redepliegue.
> - **500** en (2) → faltan las variables de Stytch en Railway (`STYTCH_PROJECT_ID`, `STYTCH_SECRET`,
>   `STYTCH_ENV`). Es config del backend, **no de tu app**.

## Conceptos

- **Usuario** = la persona (dueña de la cuenta). Se identifica por una o más **identidades** de login
  (teléfono, palma). No confundir con el "pagador" de un cobro.
- **Comercio** (`merchant`) = un negocio del usuario. Un usuario puede tener **varios**. Cada comercio
  tiene una **smart account** (dirección on-chain) donde se acumulan los cobros en USDC.
- **Custodial**: si el backend derivó la cuenta (`custodial: true`) puede **retirar**; si trajiste una
  dirección externa (`custodial: false`) no.
- **Tokens**: `accessToken` (JWT, vida 15 min) + `refreshToken` (opaco, vida 30 días, rotativo).

## Autenticación y manejo de tokens

Todas las llamadas protegidas usan el header:
```
Authorization: Bearer <accessToken>
```

Ciclo recomendado en la app:
1. Guarda `accessToken` y `refreshToken` de forma **segura** (`EncryptedSharedPreferences`).
2. Cuando una llamada devuelva **401** con `code: "invalid_token"` o `"missing_token"`, llama a
   `POST /auth/refresh` con el `refreshToken` → obtienes un **par nuevo** (guarda ambos) y reintenta.
3. Si el refresh también da **401** (`invalid_refresh_token`), la sesión murió → manda al usuario a
   re-loguearse (pantalla de teléfono).
4. Un interceptor OkHttp es el lugar ideal para (2): reintenta una vez tras refrescar.

> El `accessToken` es **stateless**: tras `DELETE /auth/me` sigue siendo válido hasta que expira
> (≤15 min), pero el `refreshToken` queda revocado. No lo trates como "sesión viva" más allá de su exp.

---

# Flujo de onboarding (desde cero)

### Paso 1 — Teléfono → enviar código
```
POST /api/v1/auth/phone/otp
Content-Type: application/json

{ "phone": "+523329728994", "channel": "whatsapp" }
```
- `phone` en **E.164** (con `+` y código de país). `channel`: `"whatsapp"` (**default y recomendado**)
  o `"sms"`. Si omites `channel`, el backend usa **WhatsApp**.
- Respuesta: **204** (sin cuerpo) → se envió el código.

> **Usa WhatsApp como canal principal.** Probado en vivo a México (`+52`) y funciona. El **SMS a
> México está bloqueado** salvo que se habilite el país `MX` en el allowlist de Stytch + tarjeta en la
> cuenta; WhatsApp no tiene ese bloqueo. Por eso `channel` default = `whatsapp`.

> **El envío de OTP usa Stytch.** En **modo test** (`STYTCH_ENV=test` en el backend) desarrollas sin
> mensajes reales: usa el teléfono sandbox **`+10000000000`** y el código **`000000`** en el Paso 2
> (devuelve 204 sin enviar nada). Para mensajes reales a tu teléfono, el backend corre con
> `STYTCH_ENV=live` (plan de pago de Stytch).

### Paso 2 — Verificar código (un solo endpoint: login-or-create)

No hay "sign in" vs "sign up": **un solo `POST /auth/verify`**. Verifica el código y emite sesión;
si la identidad no existía, **crea el usuario automáticamente**. Tu app llama siempre lo mismo.

```
POST /api/v1/auth/verify
{ "provider": "phone", "credentials": { "phone": "+523329728994", "code": "489100" } }
```
- **200** → `{ "userId", "userCreated", "accessToken", "refreshToken", "expiresIn": 900 }`.
  - `userCreated: true` → cuenta **nueva** (recién creada, sin comercios).
  - `userCreated: false` → **ya existía** (login normal).
  - En ambos casos guardas los tokens y vas a Home. (`GET /auth/me` te da el detalle de comercios.)
- **401** `code: "auth_failed"` → código OTP inválido/expirado, o sin OTP pendiente → reintenta
  (pide otro código en el Paso 1).

> El mismo endpoint sirve para palma: `{ "provider": "palm", "credentials": { "template": "…" } }`.

### Paso 3 — Estado de la cuenta (¿nuevo?, ¿qué tengo?)
```
GET /api/v1/auth/me
Authorization: Bearer <accessToken>
```
Respuesta:
```json
{
  "userId": "16149282-…",
  "isNew": true,
  "identities": [{ "provider": "phone" }],
  "merchants": []
}
```
- `isNew: true` (no tiene comercios) → manda a la pantalla de "crea tu primer comercio".
- `isNew: false` → ya tiene comercios; muéstralos (vienen en `merchants`).
- Llama a `/auth/me` al abrir la app (con el token guardado) para decidir la pantalla inicial.

### Paso 4 (opcional) — Añadir la palma a la cuenta
La app ya captura el `template` por el sensor. Para ligarlo al usuario logueado:
```
POST /api/v1/auth/link
Authorization: Bearer <accessToken>
{ "provider": "palm", "credentials": { "template": "<base64 del sensor>" } }
```
- **201** `{ "linked": true, "provider": "palm" }`. A partir de ahí el usuario también puede
  entrar con la palma: `POST /auth/verify { "provider": "palm", "credentials": { "template" } }`.
- **409** `identity_in_use` → esa palma ya es de otra cuenta.

Quitar la palma: `DELETE /api/v1/auth/identities/palm` (Bearer). **409** `cannot_remove_last_identity`
si es la única identidad (no puedes quedarte sin forma de entrar).

---

# Comercios (CRUD) — todos requieren `Authorization: Bearer`

### Crear
```
POST /api/v1/merchants
{ "name": "Cafe Brooklyn" }
```
**201** → `{ "id", "name", "smartAccountAddress", "custodial": true }`. El backend deriva la smart
account. (Opcional: manda `smartAccountAddress` para usar una dirección externa → `custodial: false`,
no podrá retirar.)

### Listar
```
GET /api/v1/merchants
```
**200** → array de `{ id, name, smartAccountAddress, custodial }` (solo los activos del usuario).

### Ver uno (con saldo on-chain)
```
GET /api/v1/merchants/{id}
```
**200** → `{ …, "usdcBalance": "12.5" }` (string, USDC con 6 decimales).

### Renombrar
```
PATCH /api/v1/merchants/{id}
{ "name": "Cafe Brooklyn Centro" }
```
**200** → comercio actualizado.

### Eliminar
```
DELETE /api/v1/merchants/{id}
```
**204** → borrado (soft-delete). **409** `code: "must_withdraw_first"` si la cuenta tiene saldo USDC:
hay que **retirar primero**.

Errores de propiedad (en GET/PATCH/DELETE/withdraw): **404** `merchant_not_found` (no existe) o
**403** `not_account_owner` (es de otro usuario).

---

# Cobros (payments) y retiros

## Cobrar (el comercio recibe USDC)

1. **Iniciar** (no requiere token — lo dispara la caja/POS):
   ```
   POST /api/v1/payments/initiate
   { "merchantId": "<id>", "amount": 4.00 }
   ```
   **201** → `{ "paymentId", "status": "pending", "wsUrl": "/ws/payments/<paymentId>" }`.
2. **Abre el WebSocket inmediatamente** (antes de autorizar), para recibir el resultado:
   ```
   wss://velt-app-android-production.up.railway.app/ws/payments/<paymentId>
   ```
3. **Autorizar** con el `personId` que resolvió el bioserver (palma del pagador):
   ```
   POST /api/v1/payments/authorize
   { "paymentId": "<id>", "personId": "<personId>" }
   ```
   Responde **202** y liquida **en segundo plano**.
4. **Resultado** por el WS: evento `authorizing` → `settled` (con `txHash`) o `failed` (con `reason`).
   El socket se cierra tras el evento terminal.
5. **Fallback** si el WS se cayó: `GET /api/v1/payments/<paymentId>` → estado actual.

## Retirar (sacar USDC del comercio) — requiere Bearer del dueño

```
POST /api/v1/merchants/{id}/withdraw
Authorization: Bearer <accessToken>
{ "to": "0x…destino", "amount": 2.50 }
```
**202** → `{ "withdrawalId", "status", "wsUrl": "/ws/withdrawals/<id>" }`. Igual que el cobro:
abre `wss://…/ws/withdrawals/<id>` para ver `processing` → `settled`/`failed`. Fallback:
`GET /api/v1/withdrawals/<id>` (Bearer). **409** `account_not_custodial` si la cuenta es externa.

---

# Formato de errores

Todos los errores tienen esta forma:
```json
{ "statusCode": 409, "code": "must_withdraw_first", "error": "Conflict", "message": "…" }
```
Decide por **`code`** (estable), no por `message` (texto en español, puede cambiar).

### Códigos que conviene manejar en la UI
| HTTP | code | Significado / acción |
|---|---|---|
| 401 | `missing_token` / `invalid_token` | refrescar token o re-loguear |
| 401 | `auth_failed` | OTP/palma inválidos o sin OTP pendiente → reintentar |
| 401 | `invalid_refresh_token` | sesión muerta → re-login |
| 409 | `identity_in_use` | (en `/auth/link`) esa palma/teléfono es de otra cuenta |
| 409 | `cannot_remove_last_identity` | no puede quitar su única identidad |
| 409 | `must_withdraw_first` | retira el saldo antes de borrar |
| 409 | `account_not_custodial` | cuenta externa: no se puede retirar |
| 403 | `not_account_owner` | el comercio es de otro usuario |
| 404 | `merchant_not_found` / `withdrawal_not_found` | no existe |
| 400 | `validation_error` / `invalid_phone` | datos mal formados |

---

# Checklist de integración Android

- [ ] Base URL apuntando a producción; confirmar build al día (curl `GET /merchants` → 401, no 404).
- [ ] Cliente HTTP (Retrofit/OkHttp) con header `Authorization` inyectado por interceptor.
- [ ] Interceptor que, ante **401** de token, llama `/auth/refresh` y reintenta una vez.
- [ ] Tokens en `EncryptedSharedPreferences` (nunca en `SharedPreferences` plano ni en logs).
- [ ] Teléfono normalizado a **E.164** antes de enviarlo (con selector de país).
- [ ] Pantalla OTP: una sola llamada `POST /auth/verify` (login-or-create); usa `userCreated` para
      diferenciar alta nueva vs login si lo necesitas.
- [ ] Pantalla inicial decidida por `GET /auth/me` (`isNew` y `merchants`).
- [ ] WebSocket (OkHttp `WebSocket`) para cobros/retiros, con fallback al `GET` de estado.

> **Nota de UX (OTP de un solo uso):** si quieres un flujo de teléfono sin el doble-OTP del caso
> "usuario nuevo", se puede unificar `register`/`login` en un solo endpoint (verifica el OTP una vez y
> crea la cuenta si no existe). Pídelo y lo agrego.
