// v1: un solo cliente por canal, sin broadcast. Si no hay socket conectado, el evento
// se descarta sin error (el estado igual queda en la DB → GET /payments/:id, /withdrawals/:id).

interface WsLike {
  send(data: string): void;
  close(): void;
  readyState: number; // 1 === OPEN (ws)
}

const WS_OPEN = 1;

// Canal = recurso namespaced. paymentIds y withdrawalIds son UUID independientes; el prefijo
// evita que dos recursos distintos compartan socket si coincidiera el UUID.
export const paymentChannel = (id: string): string => `payment:${id}`;
export const withdrawalChannel = (id: string): string => `withdrawal:${id}`;

export type PaymentEvent =
  | { type: "authorizing"; paymentId: string }
  | { type: "held"; paymentId: string; escrowTxHash: string; releaseAfter: string }
  | { type: "settled"; paymentId: string; txHash: string; payerPersonId: string }
  | { type: "failed"; paymentId: string; reason: string };

export type WithdrawalEvent =
  | { type: "processing"; withdrawalId: string }
  | { type: "settled"; withdrawalId: string; txHash: string }
  | { type: "failed"; withdrawalId: string; reason: string };

const sockets = new Map<string, WsLike>();

export function registerSocket(channel: string, socket: WsLike): void {
  sockets.set(channel, socket);
}

export function unregisterSocket(channel: string, socket: WsLike): void {
  // Solo borrar si sigue siendo el mismo socket (evita pisar una reconexión).
  if (sockets.get(channel) === socket) sockets.delete(channel);
}

function emit(channel: string, payload: unknown, terminal: boolean): void {
  const socket = sockets.get(channel);
  if (!socket || socket.readyState !== WS_OPEN) return;

  try {
    socket.send(JSON.stringify(payload));
  } catch {
    // El cliente se cayó; el estado persiste en DB. Nada más que hacer.
  }

  if (terminal) {
    try {
      socket.close();
    } catch {
      /* noop */
    }
    sockets.delete(channel);
  }
}

export function emitPaymentEvent(event: PaymentEvent): void {
  const terminal = event.type === "settled" || event.type === "failed";
  emit(paymentChannel(event.paymentId), event, terminal);
}

export function emitWithdrawalEvent(event: WithdrawalEvent): void {
  const terminal = event.type === "settled" || event.type === "failed";
  emit(withdrawalChannel(event.withdrawalId), event, terminal);
}
