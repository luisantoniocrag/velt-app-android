import type { Signer } from "./signer.js";

/**
 * Enfoque B (producción) — STUB.
 *
 * Delega custodia y firma a Turnkey: el backend pide firmas vía su API y NUNCA
 * toca la llave privada. Para completarlo:
 *
 *   1. npm i @turnkey/sdk-server @turnkey/viem (o los paquetes vigentes).
 *   2. Inicializar con TURNKEY_API_KEY (ver config.ts / .env.example).
 *   3. getOrCreateAccount: crear/obtener una private key/wallet en Turnkey por personId
 *      y derivar la dirección de su smart account.
 *   4. signAndSendUserOp: usar el account de Turnkey como owner de la SimpleSmartAccount
 *      (permissionless/viem) para firmar la UserOp y enviarla al bundler.
 *
 * Selecciónalo con SIGNER_BACKEND=turnkey. Ver README.md (Enfoque A vs B).
 */
export class TurnkeySigner implements Signer {
  async getOrCreateAccount(_personId: string): Promise<{ address: string }> {
    throw new Error("TurnkeySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }

  async signAndSendUserOp(_params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    throw new Error("TurnkeySigner not implemented — usa SIGNER_BACKEND=local (ver README)");
  }
}
