import type { FastifyInstance } from "fastify";
import { paymentChannel, registerSocket, unregisterSocket } from "../lib/events.js";

export async function paymentWsRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { paymentId: string } }>(
    "/ws/payments/:paymentId",
    { websocket: true },
    (socket, req) => {
      const channel = paymentChannel(req.params.paymentId);
      registerSocket(channel, socket);
      socket.on("close", () => unregisterSocket(channel, socket));
      socket.on("error", () => unregisterSocket(channel, socket));
    },
  );
}
