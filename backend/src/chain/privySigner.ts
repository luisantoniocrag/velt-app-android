import type { Signer } from "./signer.js";

/**
 * Enfoque B (producción) — STUB.
 *
 * Delega custodia y firma a Privy: el backend pide firmas vía su SDK y NUNCA
 * toca la llave privada. Para completarlo:
 *
 *   1. npm i @privy-io/server-auth (o el SDK server de Privy vigente).
 *   2. Inicializar con PRIVY_APP_ID / PRIVY_APP_SECRET (ver config.ts / .env.example).
 *   3. getOrCreateAccount: crear/obtener la wallet del usuario en Privy (key por personId)
 *      y devolver su dirección de smart account.
 *   4. signAndSendUserOp: construir la UserOp con permissionless/viem, pedir la firma
 *      a Privy (remote signer) y enviarla al bundler.
 *
 * Selecciónalo con SIGNER_BACKEND=privy. Ver README.md (Enfoque A vs B).
 */
export class PrivySigner implements Signer {
  async getOrCreateAccount(_personId: string): Promise<{ address: string }> {
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
