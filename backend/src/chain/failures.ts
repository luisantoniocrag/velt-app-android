// Clasifica un error on-chain en un motivo estable para el evento WS / la respuesta HTTP.
// Compartido por la liquidación de pagos y la de retiros.
export type FailureReason =
  | "insufficient_funds"
  | "rpc_timeout"
  | "tx_reverted"
  | "payment_failed";

export function classifyFailure(err: unknown): FailureReason {
  const msg = (err instanceof Error ? err.message : String(err)).toLowerCase();
  if (msg.includes("insufficient") || msg.includes("balance") || msg.includes("funds")) {
    return "insufficient_funds";
  }
  if (msg.includes("timeout") || msg.includes("timed out")) return "rpc_timeout";
  if (msg.includes("revert")) return "tx_reverted";
  return "payment_failed";
}
