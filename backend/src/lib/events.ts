// v1: un solo cliente por pago, sin broadcast. Si no hay socket conectado, el evento
// se descarta sin error (el estado igual queda en la DB → GET /payments/:id).

interface WsLike {
  send(data: string): void;
  close(): void;
  readyState: number; // 1 === OPEN (ws)
}

const WS_OPEN = 1;

export type PaymentEvent =
  | { type: "authorizing"; paymentId: string }
  | { type: "settled"; paymentId: string; txHash: string; payerPersonId: string }
  | { type: "failed"; paymentId: string; reason: string };

const sockets = new Map<string, WsLike>();

export function registerSocket(paymentId: string, socket: WsLike): void {
  sockets.set(paymentId, socket);
}

export function unregisterSocket(paymentId: string, socket: WsLike): void {
  // Solo borrar si sigue siendo el mismo socket (evita pisar una reconexión).
  if (sockets.get(paymentId) === socket) sockets.delete(paymentId);
}

export function emitPaymentEvent(event: PaymentEvent): void {
  const socket = sockets.get(event.paymentId);
  if (!socket || socket.readyState !== WS_OPEN) return;

  try {
    socket.send(JSON.stringify(event));
  } catch {
    // El cliente se cayó; el estado persiste en DB. Nada más que hacer.
  }

  if (event.type === "settled" || event.type === "failed") {
    try {
      socket.close();
    } catch {
      /* noop */
    }
    sockets.delete(event.paymentId);
  }
}
