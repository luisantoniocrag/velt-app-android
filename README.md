# Velt — pay with your palm

**Velt turns your palm into your wallet.** Enroll once, then pay any merchant in ~2 seconds by
placing your hand on the Velt sensor — no card, no phone, no PIN. Funds settle instantly as **USDC
on Arc** through an on-chain conditional escrow; every user gets a **non-custodial wallet** (Dynamic
MPC) and a human-readable **ENS identity**; merchants see live balances and can withdraw — even
privately. All the crypto is invisible to the user.

```
.
├── android-app/   # Android app (Kotlin + Jetpack Compose) — drives the palm sensor
└── backend/       # Node 20 + TypeScript + Fastify — orchestrates wallets, escrow, ENS, payouts
```

- **Sensor:** the app wakes the Velt palm sensor over BLE, captures the biometric template over
  SPP/RFCOMM, and identifies it against the OpenPalm bioserver → a stable `personId`.
- **Backend:** a `Signer` abstraction backed by **Dynamic Server Wallets** (TSS-MPC) signs every
  transaction on **Arc**. Payments run through `VeltEscrow.sol` (hold → release/refund). Real-time
  status over WebSocket. Full architecture + flows: [`backend/FLOWS.md`](backend/FLOWS.md).

---

## ✅ Verifiable on-chain deliverables (proof it works)

> The values below are from a **real end-to-end run** (palm → MPC wallet → escrow hold → release →
> settled, with an auto-issued ENS name). Replace any `‹FILL…›` with the explorer link / your final
> demo tx when you re-run the flow for the recording.

### 🟠 Arc — payments settle in USDC via on-chain escrow

| Item | Value |
|---|---|
| Network | Arc testnet · RPC `https://rpc.testnet.arc.network` · chainId `5042002` |
| USDC | `0x3600000000000000000000000000000000000000` |
| **VeltEscrow contract** | `0x9f13607afeb65f73b005dc7ae923aa4c49662c78` |
| Escrow **hold** tx (payer → escrow, 2 USDC) | `0x123aefcb688f2a05967c76b2e51aeb0c5304d4f3577cad978b0c86bba2e27043` |
| Escrow **release** tx (escrow → merchant `tonylatte.velt.eth`) | `0xef4cace8af4811e4524f4e3ff6ebcf5bc2219b1240e3712fbccaeccd3173026a` |
| Explorer links | `‹FILL: <ARC_EXPLORER_URL>/tx/0x123aefcb…›` · `‹FILL: <ARC_EXPLORER_URL>/tx/0xef4cace8…›` |
| Code | [`backend/contracts/VeltEscrow.sol`](backend/contracts/VeltEscrow.sol) · settlement in [`backend/src/routes/payments.ts`](backend/src/routes/payments.ts) |

`‹FILL: optional — a fresh hold+release tx pair from your final demo run›`

### 🔵 ENS — every user/merchant gets a subname that resolves to their wallet

| Item | Value |
|---|---|
| Network | Sepolia (ENSv2) |
| Parent | `velt.eth` · registry `0x7df8b8f316d5d4c1077612e2b32f93635bd40c0f` · resolver `0xb39AB6Eb190C055656ae07C52E3cD8c393FF4cE8` |
| **Merchant subname** (chosen at merchant creation) | `tonylatte.velt.eth` → resolves to `0xD71acDC244dDbA8b7EE6eB55dc37F5BeBccd9f16` |
| **Payer subname** (auto-issued on first payment) | `palm-8989fd-2.velt.eth` → resolves to `0xAda644A1fBa0481CE23Ff9B357F8Cf9d5e5F5fb5` |
| Verify resolution | `‹FILL: explorer.ens.dev/tonylatte.velt.eth (or your ENS app link)›` |
| Code | [`backend/src/ens/registrar.ts`](backend/src/ens/registrar.ts) (`register()` + resolver `setAddr()`) |

`‹FILL: optional — a subname you registered live during the demo›`

### 🟣 Dynamic — TSS-MPC Server Wallets sign everything

| Item | Value |
|---|---|
| SDK | `@dynamic-labs-wallet/node-evm` (Server Wallets, TSS-MPC) |
| Payer MPC wallet (signed the Arc escrow hold above) | `0xAda644A1fBa0481CE23Ff9B357F8Cf9d5e5F5fb5` |
| Operator MPC wallet (signed the release above) | `0x8A3B4a793665BAC175327Fe38E0cD78498b76110` |
| Merchant MPC wallet (`tonylatte.velt.eth`, receives payments) | `0xD71acDC244dDbA8b7EE6eB55dc37F5BeBccd9f16` |
| Proof | The Arc **hold** tx was signed by the payer MPC wallet; the **release** tx by the operator MPC wallet (see Arc table above) |
| Code | [`backend/src/chain/dynamicSigner.ts`](backend/src/chain/dynamicSigner.ts) (`createWalletAccount`, `getWalletClient` → viem on Arc) |

`‹FILL: optional — Dynamic dashboard screenshot / wallet IDs›`

> **One run, three proofs:** the same end-to-end payment shows all three at once — a **Dynamic**
> MPC wallet signed an **Arc** escrow transaction, and the merchant resolves to an **ENS** name.

---

## Build & run

**Android app**
```bash
cd android-app
./gradlew :app:installDebug      # build + install on a connected device/emulator (needs the sensor for palm flows)
```
`compileSdk 36`, `minSdk 29`, namespace `com.velt`. Points to the backend via `BuildConfig.API_BASE_URL`.

**Backend**
```bash
cd backend
npm install
cp .env.example .env             # fill credentials; see backend/README.md
npm run dev                      # http://localhost:3000
npm run typecheck                # source of truth for the build
```

## How to reproduce the proof

A scripted end-to-end run (`backend/scripts/setup-payment-test.ts` → `initiate` → `authorize` →
`confirm`) is documented step-by-step in [`backend/FLOWS.md`](backend/FLOWS.md) under **"🧪 Cómo
probar"**. It produces a real hold + release on Arc, auto-issues the ENS subname, and signs with
Dynamic MPC wallets.

## Docs

- [`backend/FLOWS.md`](backend/FLOWS.md) — all flows + a "how to test" recipe.
- [`backend/README.md`](backend/README.md) — backend setup & env vars.
- [`CLAUDE.md`](CLAUDE.md) — sensor protocol, app navigation, implementation notes.
