import { config } from "../config.js";

/**
 * Capa de firma desacoplada. El resto del backend solo conoce esta interfaz;
 * la implementación concreta se elige por SIGNER_BACKEND (local | privy | turnkey).
 *
 * - LocalSigner  (Enfoque A): el backend custodia una master key y firma directo. Implementado.
 * - PrivySigner / TurnkeySigner (Enfoque B): firma externa, el backend nunca toca la llave. Stubs.
 *
 * Ver README.md para la comparación A vs B y el camino de producción.
 */
export interface Signer {
  /**
   * Devuelve la smart account asociada a un personId, derivándola/desplegándola
   * de forma determinista si es la primera vez que se ve.
   */
  getOrCreateAccount(personId: string): Promise<{ address: string }>;

  /**
   * Construye, firma y envía una UserOperation que transfiere `amountUsdc`
   * (en unidades base, 6 decimales) desde la smart account `from` hacia `to`.
   * Espera el recibo y devuelve el hash de la transacción on-chain.
   */
  signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }>;
}

let cached: Signer | null = null;

/** Selector singleton del signer según la configuración. */
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
