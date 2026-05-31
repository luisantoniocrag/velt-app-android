import type { FastifyInstance } from "fastify";
import { registerSocket, unregisterSocket } from "../lib/events.js";

/**
 * WebSocket de pagos: GET /ws/payments/:paymentId (upgrade).
 *
 * La app del comerciante abre el WS justo después de initiate. El backend emite
 * authorizing / settled / failed conforme avanza el pago (ver lib/events.ts), y
 * cierra el socket tras un evento terminal. Si el WS no está conectado cuando
 * ocurre el evento, el estado igual queda en la DB (GET /payments/:id).
 */
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
