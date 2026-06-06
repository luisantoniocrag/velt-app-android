import type { Signer } from "./signer.js";

// STUB (Enfoque B, producción): firma remota vía Privy. Ver README.md.
export class PrivySigner implements Signer {
  async getOrCreateAccount(_subjectId: string): Promise<{ address: string }> {
    throw new Error("PrivySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }

  async signAndSendUserOp(_params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    throw new Error("PrivySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }
}
