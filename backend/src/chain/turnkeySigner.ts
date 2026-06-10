import type { ChainCall, Signer } from "./signer.js";

// STUB (Enfoque B, producción): firma remota vía Turnkey. Ver README.md.
export class TurnkeySigner implements Signer {
  async getOrCreateAccount(_subjectId: string): Promise<{ address: string }> {
    throw new Error("TurnkeySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }

  async signAndSendUserOp(_params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    throw new Error("TurnkeySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }

  async signAndSendCalls(_params: { from: string; calls: ChainCall[] }): Promise<{ txHash: string }> {
    throw new Error("TurnkeySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }
}
