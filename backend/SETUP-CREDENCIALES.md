# Credenciales del backend — paso a paso

Guía para llenar el `.env` (copia de [`.env.example`](.env.example)) y poder correr el flujo
de punta a punta. Hay **3 grupos**: Supabase, Arc/ERC-4337 y el firmante (signer).

> Cuando termines, valida con `cd backend && npm run dev`. Si falta algo o tiene mal formato,
> el server falla al arrancar y te dice exactamente qué variable corregir (validación zod).

## Checklist rápido

| Variable | De dónde sale | Dificultad |
|---|---|---|
| `SUPABASE_URL` | Supabase → Project Settings → API | fácil |
| `SUPABASE_SERVICE_KEY` | Supabase → Project Settings → API (`service_role`) | fácil |
| `ARC_RPC_URL` | Circle / proveedor RPC de Arc | media |
| `ARC_CHAIN_ID` | docs de Arc | fácil |
| `USDC_CONTRACT_ADDRESS` | docs de Circle (USDC en Arc) | fácil |
| `ERC4337_BUNDLER_URL` | proveedor de bundler (Pimlico, Alchemy…) | media |
| `ERC4337_ENTRYPOINT_ADDRESS` | constante canónica (abajo) | trivial |
| `SIGNER_BACKEND` | decisión: `local` para empezar | trivial |
| `LOCAL_SIGNER_MASTER_KEY` | la generas tú (comando abajo) | fácil |

---

## 1. Supabase (base de datos)

Velt usa Supabase como PostgreSQL gestionado.

1. Entra a **https://supabase.com** y crea una cuenta (gratis).
2. **New project** → ponle nombre (ej. `velt-backend`), elige una contraseña de base de datos
   (guárdala, no va al `.env`) y una región cercana. Espera ~2 min a que se provisione.
3. En el proyecto, ve a **Project Settings** (engranaje) → **API**. Ahí tienes:
   - **Project URL** → es tu `SUPABASE_URL` (ej. `https://abcdxyz.supabase.co`).
   - En **Project API keys**, la clave **`service_role`** (la marcada como *secret*) → es tu
     `SUPABASE_SERVICE_KEY`.
4. **Crea las tablas:** abre **SQL Editor** → **New query**, pega TODO el contenido de
   [`src/db/schema.sql`](src/db/schema.sql) y dale **Run**. Debe crear `merchants`,
   `velt_users` y `payment_requests`.

> ⚠️ La `service_role` key salta las reglas de seguridad (RLS). Es de servidor: nunca la pongas
> en la app Android ni en el front. Solo vive en el `.env` del backend.

```
SUPABASE_URL=https://TU-PROYECTO.supabase.co
SUPABASE_SERVICE_KEY=eyJhbGciOi...   # la service_role
```

---

## 2. Arc + USDC (blockchain)

**Arc** es la blockchain de **Circle**. Como es una red nueva, los valores exactos (RPC, chainId,
dirección de USDC) **deben tomarse de la documentación oficial de Circle**, no inventarse.

### 2.1 `ARC_RPC_URL` y `ARC_CHAIN_ID`

1. Ve al portal de desarrolladores de Circle / Arc: **https://developers.circle.com** (busca la
   sección **Arc** → *Testnet*).
2. Necesitas acceso a **Arc Testnet**. Según el estado del programa puede requerir registro o
   estar en acceso anticipado — si no ves la red, solicita acceso ahí mismo.
3. De ahí copia:
   - El **RPC endpoint HTTPS** de la testnet → `ARC_RPC_URL`.
   - El **Chain ID** de la testnet → `ARC_CHAIN_ID` (un número).
4. Verifica el RPC con un ping rápido (devuelve el chainId en hex):
   ```bash
   curl -s -X POST "$ARC_RPC_URL" \
     -H 'content-type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}'
   ```
   Convierte el hex a decimal y confirma que coincide con `ARC_CHAIN_ID`.

### 2.2 `USDC_CONTRACT_ADDRESS`

- La dirección del contrato **USDC en Arc Testnet** la publica Circle en su doc de
  *“USDC contract addresses”* (página oficial de direcciones de USDC por red).
- Copia la dirección de Arc Testnet (formato `0x` + 40 hex) → `USDC_CONTRACT_ADDRESS`.
- Para probar pagos reales necesitarás **USDC de testnet**: consigue del **faucet** de Circle
  para Arc (en el mismo portal). Si la cuenta del pagador queda sin fondos, el pago se marca
  `failed` por saldo insuficiente — que es el comportamiento correcto de v1.

### 2.3 `ERC4337_BUNDLER_URL`

El backend usa cuentas inteligentes (ERC-4337). Un **bundler** es el servicio que recibe las
*UserOperations* y las mete on-chain. No lo corres tú; lo da un proveedor:

1. Crea cuenta en un proveedor de bundler que **soporte Arc**. Opciones comunes:
   - **Pimlico** — https://pimlico.io
   - **Alchemy** (Account Kit) — https://alchemy.com
   - **Candide**, **Biconomy**, etc.
2. Crea una **API key** y selecciona la red **Arc (testnet)**.
3. El proveedor te da una **URL de bundler** (suele incluir tu API key y el chainId). Esa URL →
   `ERC4337_BUNDLER_URL`.

> Si el proveedor ofrece **paymaster** (gas patrocinado) puede servir más adelante, pero para v1
> no es obligatorio: la cuenta paga su propio gas. (Funding llega con Blink en v2.)

### 2.4 `ERC4337_ENTRYPOINT_ADDRESS`

Es una **constante canónica** del estándar, igual en todas las redes EVM. El backend usa
**EntryPoint v0.7**:

```
ERC4337_ENTRYPOINT_ADDRESS=0x0000000071727De22E5E9d8BAf0edAc6f37da032
```

> Solo cámbiala si tu bundler te indica explícitamente otra versión/dirección. Si usaras v0.6
> habría que ajustar también la versión en `src/chain/localSigner.ts` (`version: "0.7"`).

---

## 3. Firmante (signer)

### `SIGNER_BACKEND`

Para empezar (hackathon), déjalo en `local` (Enfoque A, ya implementado):

```
SIGNER_BACKEND=local
```

(Producción = `privy` o `turnkey`, hoy stubs. Ver [`README.md`](README.md).)

### `LOCAL_SIGNER_MASTER_KEY`

Es una llave privada de 32 bytes (hex `0x...`) que el backend usa como semilla maestra para
derivar el owner de cada cuenta. **Genérala tú**, nunca la reutilices de otra cosa, y trátala
como secreto (si se filtra, se comprometen todas las cuentas derivadas).

Genera una con cualquiera de estos comandos:

```bash
# opción A — node (viem)
node -e "import('viem/accounts').then(m=>console.log(m.generatePrivateKey()))"

# opción B — openssl
echo "0x$(openssl rand -hex 32)"
```

Pega el resultado:

```
LOCAL_SIGNER_MASTER_KEY=0x<64-hex>
```

---

## 4. Resultado final

Tu `.env` (copiado de `.env.example`) debe quedar parecido a:

```
PORT=3000

SUPABASE_URL=https://TU-PROYECTO.supabase.co
SUPABASE_SERVICE_KEY=eyJhbGciOi...

ARC_RPC_URL=https://<rpc-de-arc-testnet>
ARC_CHAIN_ID=<numero>
USDC_CONTRACT_ADDRESS=0x<usdc-en-arc>
ERC4337_BUNDLER_URL=https://<bundler-con-tu-api-key>
ERC4337_ENTRYPOINT_ADDRESS=0x0000000071727De22E5E9d8BAf0edAc6f37da032

SIGNER_BACKEND=local
LOCAL_SIGNER_MASTER_KEY=0x<64-hex>
```

Luego:

```bash
cd backend
cp .env.example .env      # y rellénalo con lo de arriba
npm install
npm run dev               # arranca; si algo falta, te dice qué variable
```

Y ejecuta el flujo 1→6 con [`requests.http`](requests.http) (más `wscat` para el WebSocket).

> Recordatorio: el `.env` está en `.gitignore`. **Nunca lo commitees.**
