import { config } from "../config.js";

// Capa de firma desacoplada; la implementación se elige por SIGNER_BACKEND. Ver README.md.
export interface Signer {
  getOrCreateAccount(personId: string): Promise<{ address: string }>;
  signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }>;
}

let cached: Signer | null = null;

export async function getSigner(): Promise<Signer> {
  if (cached) return cached;

  switch (config.SIGNER_BACKEND) {
    case "local": {
      const { LocalSigner } = await import("./localSigner.js");
      cached = new LocalSigner();
      return cached;
    }
    case "privy": {
      const { PrivySigner } = await import("./privySigner.js");
      cached = new PrivySigner();
      return cached;
    }
    case "turnkey": {
      const { TurnkeySigner } = await import("./turnkeySigner.js");
      cached = new TurnkeySigner();
      return cached;
    }
  }
}
