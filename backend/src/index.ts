import Fastify, { type FastifyError } from "fastify";
import websocket from "@fastify/websocket";
import { config } from "./config.js";
import { AppError } from "./lib/errors.js";
import { merchantRoutes } from "./routes/merchants.js";
import { paymentRoutes } from "./routes/payments.js";
import { paymentWsRoutes } from "./ws/payments.js";

async function main(): Promise<void> {
  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      redact: ["req.headers.authorization", "*.SUPABASE_SERVICE_KEY", "*.LOCAL_SIGNER_MASTER_KEY"],
    },
  });

  await app.register(websocket);

  app.get("/health", async () => ({ ok: true }));

  await app.register(
    async (api) => {
      await api.register(merchantRoutes);
      await api.register(paymentRoutes);
    },
    { prefix: "/api/v1" },
  );

  await app.register(paymentWsRoutes);

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

  await app.listen({ port: config.PORT, host: "0.0.0.0" });
}

main().catch((err) => {
  console.error("[index] fallo al arrancar:", err);
  process.exit(1);
});
