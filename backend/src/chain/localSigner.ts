import { createSmartAccountClient } from "permissionless";
import { toSimpleSmartAccount } from "permissionless/accounts";
import { privateKeyToAccount } from "viem/accounts";
import { http, keccak256, toHex, type Address } from "viem";
import { config } from "../config.js";
import type { Signer } from "./signer.js";
import { arcChain, publicClient, USDC_ADDRESS, encodeUsdcTransfer } from "./usdc.js";

/**
 * Enfoque A (hackathon): el backend custodia la master key y firma directamente.
 *
 * El "owner" de cada smart account ERC-4337 se deriva de forma DETERMINISTA del
 * personId + la master key, así que la dirección de la cuenta es estable y
 * reproducible sin guardar la llave por usuario:
 *
 *     ownerPrivKey = keccak256("<masterKey>:<personId>")
 *
 * Riesgo: si se filtra LOCAL_SIGNER_MASTER_KEY, se comprometen todas las cuentas.
 * El camino de producción (Privy/Turnkey) está en privySigner.ts/turnkeySigner.ts
 * y documentado en README.md.
 */
export class LocalSigner implements Signer {
  private readonly entryPoint = {
    address: config.ERC4337_ENTRYPOINT_ADDRESS as Address,
    version: "0.7" as const,
  };

  /** Deriva el owner EOA determinista para un personId. */
  private ownerFor(personId: string) {
    const masterKey = config.LOCAL_SIGNER_MASTER_KEY!; // validado en config.ts cuando SIGNER_BACKEND=local
    const privKey = keccak256(toHex(`${masterKey}:${personId}`));
    return privateKeyToAccount(privKey);
  }

  /** Construye la SimpleSmartAccount ERC-4337 para un personId. */
  private async accountFor(personId: string) {
    return toSimpleSmartAccount({
      client: publicClient,
      owner: this.ownerFor(personId),
      entryPoint: this.entryPoint,
    });
  }

  // Mapa address→personId poblado por getOrCreateAccount; permite a
  // signAndSendUserOp re-derivar el owner conociendo solo la dirección `from`.
  // El caller (payments.authorize) SIEMPRE llama getOrCreateAccount (idempotente)
  // antes de firmar, así que el mapa está caliente en el proceso vigente.
  private readonly addressToPerson = new Map<string, string>();

  async getOrCreateAccount(personId: string): Promise<{ address: string }> {
    const account = await this.accountFor(personId);
    // La dirección es contrafactual: válida aunque la cuenta aún no esté desplegada.
    // El primer envío de UserOp incluye el initCode que la despliega.
    this.addressToPerson.set(account.address.toLowerCase(), personId);
    return { address: account.address };
  }

  async signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    // `from` lo dicta el personId; recuperamos la cuenta para firmar.
    // (params.from se valida contra la dirección derivada en el caller.)
    const account = await this.accountForAddress(params.from);

    const smartAccountClient = createSmartAccountClient({
      account,
      chain: arcChain,
      bundlerTransport: http(config.ERC4337_BUNDLER_URL),
    });

    // sendTransaction empaqueta la llamada en una UserOperation, la firma, la envía
    // al bundler y espera el recibo; devuelve el hash de la tx on-chain.
    const txHash = await smartAccountClient.sendTransaction({
      to: USDC_ADDRESS,
      data: encodeUsdcTransfer(params.to as Address, params.amountUsdc),
      value: 0n,
    });

    return { txHash };
  }

  /** Reconstruye la cuenta a partir de su dirección, vía el mapa address→personId. */
  private async accountForAddress(address: string) {
    const personId = this.addressToPerson.get(address.toLowerCase());
    if (!personId) {
      throw new Error(`no hay personId registrado para la cuenta ${address}`);
    }
    return this.accountFor(personId);
  }
}
