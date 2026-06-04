import type { FastifyInstance } from "fastify";
import { registerSocket, unregisterSocket } from "../lib/events.js";

export async function paymentWsRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { paymentId: string } }>(
    "/ws/payments/:paymentId",
    { websocket: true },
    (socket, req) => {
      const { paymentId } = req.params;
      registerSocket(paymentId, socket);
      socket.on("close", () => unregisterSocket(paymentId, socket));
      socket.on("error", () => unregisterSocket(paymentId, socket));
    },
  );
}
