import type { FastifyInstance } from "fastify";
import { requireBlink } from "./blink.js";

// Payer funding page, opened by the Android app in a Chrome Custom Tab:
// /fund?personId=<id> → Blink hosted deposit modal → deep link back to velt://deposit.
// Static HTML (personId is read client-side from the query string, never interpolated
// server-side) with the web SDK loaded from a pinned ESM CDN build — no bundler needed.
const FUND_PAGE_HTML = `<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>Velt — Fondear cuenta</title>
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; margin: 0; }
    body {
      min-height: 100dvh; display: flex; align-items: center; justify-content: center;
      background: #0b0f14; color: #e6edf3;
      font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    }
    main { width: min(92vw, 420px); padding: 28px; }
    h1 { font-size: 1.3rem; margin-bottom: 4px; }
    h1 span { color: #22d3ee; }
    p.sub { color: #8b949e; font-size: 0.9rem; margin-bottom: 24px; }
    label { display: block; font-size: 0.8rem; color: #8b949e; margin-bottom: 6px; }
    input {
      width: 100%; padding: 14px; font-size: 1.4rem; border-radius: 12px;
      border: 1px solid #1f2937; background: #111827; color: #e6edf3; margin-bottom: 16px;
    }
    input:focus { outline: 2px solid #22d3ee; border-color: transparent; }
    button {
      width: 100%; padding: 16px; font-size: 1rem; font-weight: 600;
      border: none; border-radius: 12px; background: #22d3ee; color: #06222a; cursor: pointer;
    }
    button:disabled { opacity: 0.5; cursor: default; }
    .address { font-family: ui-monospace, monospace; font-size: 0.8rem; color: #8b949e;
      background: #111827; border-radius: 8px; padding: 10px; margin-bottom: 16px;
      overflow-wrap: anywhere; }
    .msg { margin-top: 16px; font-size: 0.9rem; }
    .msg.error { color: #f87171; }
    .msg.ok { color: #34d399; }
    a.back { color: #22d3ee; }
    .hidden { display: none; }
  </style>
</head>
<body>
  <main>
    <h1>Fondear con <span>Blink</span></h1>
    <p class="sub">Deposita USDC en tu cuenta Velt (Base)</p>
    <div id="form" class="hidden">
      <label for="amount">Monto (USD)</label>
      <input id="amount" type="number" min="1" step="1" value="10" inputmode="decimal" />
      <label>Cuenta destino</label>
      <div class="address" id="address"></div>
      <button id="fund-btn">Fund with Blink</button>
    </div>
    <div id="msg" class="msg"></div>
  </main>
  <script type="module">
    import { Deposit, DepositError, getDisplayMessage }
      from "https://esm.sh/@swype-org/deposit@0.3.16";

    const form = document.getElementById("form");
    const msg = document.getElementById("msg");
    const btn = document.getElementById("fund-btn");
    const amountInput = document.getElementById("amount");

    const showError = (text) => { msg.className = "msg error"; msg.textContent = text; };
    const deepLink = (params) => {
      const qs = new URLSearchParams(params).toString();
      location.href = "velt://deposit?" + qs;
    };

    const personId = new URLSearchParams(location.search).get("personId");
    if (!personId) {
      showError("Falta personId en la URL.");
    } else {
      init(personId);
    }

    async function init(personId) {
      let ctx;
      try {
        const res = await fetch("/api/v1/deposits/context?personId=" + encodeURIComponent(personId));
        if (!res.ok) throw new Error("context " + res.status);
        ctx = await res.json();
      } catch (err) {
        showError("No se pudo cargar tu cuenta. Intenta de nuevo.");
        return;
      }

      document.getElementById("address").textContent = ctx.address;
      form.classList.remove("hidden");

      const deposit = new Deposit({ signer: "/api/v1/blink/sign-payment" });
      addEventListener("pagehide", () => deposit.destroy());

      btn.addEventListener("click", async () => {
        const amount = Number(amountInput.value);
        if (!Number.isFinite(amount) || amount <= 0) {
          showError("Monto inválido.");
          return;
        }
        btn.disabled = true;
        msg.textContent = "";
        try {
          const { transfer } = await deposit.requestDeposit({
            amount,
            chainId: ctx.chainId,
            address: ctx.address,
            token: ctx.token,
          });
          await fetch("/api/v1/deposits/record", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              personId,
              transferId: transfer.id,
              status: transfer.status,
              amount,
              chainId: ctx.chainId,
            }),
          }).catch(() => {});
          msg.className = "msg ok";
          msg.textContent = "Depósito completado. Volviendo a Velt...";
          deepLink({ status: "ok", transferId: transfer.id });
        } catch (err) {
          const text = err instanceof DepositError ? getDisplayMessage(err) : "El depósito falló.";
          showError(text);
          msg.insertAdjacentHTML("beforeend",
            ' <a class="back" href="velt://deposit?status=error">Volver a Velt</a>');
        } finally {
          btn.disabled = false;
        }
      });
    }
  </script>
</body>
</html>
`;

export async function fundRoutes(app: FastifyInstance): Promise<void> {
  app.get("/fund", async (_request, reply) => {
    requireBlink();
    return reply.header("Cache-Control", "no-store").type("text/html; charset=utf-8").send(FUND_PAGE_HTML);
  });
}
