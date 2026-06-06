import { config } from "../config.js";

// Capa de firma desacoplada; la implementación se elige por SIGNER_BACKEND. Ver README.md.
export interface Signer {
  getOrCreateAccount(subjectId: string): Promise<{ address: string }>;
  signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }>;
}

// El subject es la entrada de la derivación determinista del owner. Los pagadores usan su
// personId crudo (histórico, no romper direcciones ya derivadas); los comerciantes se
// namespacing para que un personId nunca pueda colisionar con un merchantId.
export const subjectForMerchant = (merchantId: string): string => `merchant:${merchantId}`;

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
