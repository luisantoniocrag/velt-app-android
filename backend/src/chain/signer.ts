import { config } from "../config.js";

export interface ChainCall {
  to: string;
  data: `0x${string}`;
  value?: bigint;
}

// Capa de firma desacoplada; la implementación se elige por SIGNER_BACKEND. Ver README.md.
export interface Signer {
  getOrCreateAccount(subjectId: string): Promise<{ address: string }>;
  signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }>;
  signAndSendCalls(params: { from: string; calls: ChainCall[] }): Promise<{ txHash: string }>;
}

// El subject es la entrada de la derivación determinista del owner. Los pagadores usan su
// personId crudo (histórico, no romper direcciones ya derivadas); los comerciantes se
// namespacing para que un personId nunca pueda colisionar con un merchantId.
export const subjectForMerchant = (merchantId: string): string => `merchant:${merchantId}`;

// Backend-owned smart account that operates the escrow (release/refund). Same deterministic
// derivation as payers and merchants; it needs gas like any other account.
export const OPERATOR_SUBJECT = "operator";

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
