import Fastify, { type FastifyError } from "fastify";
import websocket from "@fastify/websocket";
import { config } from "./config.js";
import { AppError } from "./lib/errors.js";
import { authRoutes } from "./routes/auth.js";
import { merchantRoutes } from "./routes/merchants.js";
import { paymentRoutes, startEscrowAutoRelease } from "./routes/payments.js";
import { blinkRoutes, blinkEnabled } from "./routes/blink.js";
import { payerRoutes } from "./routes/payers.js";
import { fundRoutes } from "./routes/fund.js";
import { ensEnabled } from "./ens/registrar.js";
import { paymentWsRoutes } from "./ws/payments.js";
import { withdrawalWsRoutes } from "./ws/withdrawals.js";

async function main(): Promise<void> {
  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      redact: [
        "req.headers.authorization",
        "*.SUPABASE_SERVICE_KEY",
        "*.LOCAL_SIGNER_MASTER_KEY",
        "*.JWT_SECRET",
        "*.BIOSERVER_SHARED_SECRET",
        "*.BLINK_MERCHANT_PRIVATE_KEY",
      ],
    },
  });

  await app.register(websocket);

  // Must be set BEFORE awaiting route registrations: awaited children boot immediately and
  // do not inherit an error handler added to the parent afterwards.
  app.setErrorHandler((err: FastifyError, request, reply) => {
    if (err instanceof AppError) {
      return reply.code(err.statusCode).send({ error: err.code, message: err.message });
    }
    if (typeof err.statusCode === "number" && err.statusCode >= 400 && err.statusCode < 500) {
      return reply.code(err.statusCode).send({ error: "bad_request", message: err.message });
    }
    request.log.error({ err }, "error no controlado");
    return reply.code(500).send({ error: "internal_error", message: "error interno" });
  });

  app.get("/health", async () => ({ ok: true }));

  await app.register(
    async (api) => {
      await api.register(authRoutes);
      await api.register(merchantRoutes);
      await api.register(paymentRoutes);
      await api.register(payerRoutes);
      await api.register(blinkRoutes);
    },
    { prefix: "/api/v1" },
  );

  await app.register(fundRoutes);
  await app.register(paymentWsRoutes);
  await app.register(withdrawalWsRoutes);

  await app.listen({ port: config.PORT, host: "0.0.0.0" });

  startEscrowAutoRelease(app.log);

  if (!ensEnabled()) {
    app.log.warn(
      "ENS deshabilitado: faltan ENS_PARENT_NAME / ENS_OWNER_PRIVATE_KEY / SEPOLIA_RPC_URL",
    );
  }
  if (!blinkEnabled()) {
    app.log.warn(
      "Blink deshabilitado: faltan BLINK_MERCHANT_ID / BLINK_MERCHANT_PRIVATE_KEY (rutas → 503)",
    );
  }
}

main().catch((err) => {
  console.error("[index] fallo al arrancar:", err);
  process.exit(1);
});
