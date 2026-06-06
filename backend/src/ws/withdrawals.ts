import type { FastifyInstance } from "fastify";
import { registerSocket, unregisterSocket, withdrawalChannel } from "../lib/events.js";

export async function withdrawalWsRoutes(app: FastifyInstance): Promise<void> {
  app.get<{ Params: { withdrawalId: string } }>(
    "/ws/withdrawals/:withdrawalId",
    { websocket: true },
    (socket, req) => {
      const channel = withdrawalChannel(req.params.withdrawalId);
      registerSocket(channel, socket);
      socket.on("close", () => unregisterSocket(channel, socket));
      socket.on("error", () => unregisterSocket(channel, socket));
    },
  );
}
