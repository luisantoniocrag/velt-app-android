import { createSmartAccountClient } from "permissionless";
import { toSimpleSmartAccount } from "permissionless/accounts";
import { privateKeyToAccount } from "viem/accounts";
import { http, keccak256, toHex, type Address } from "viem";
import { config } from "../config.js";
import type { Signer } from "./signer.js";
import { arcChain, publicClient, USDC_ADDRESS, encodeUsdcTransfer } from "./usdc.js";

// Owner determinista por usuario: ownerPrivKey = keccak256("<masterKey>:<personId>").
// Riesgo: si se filtra LOCAL_SIGNER_MASTER_KEY, se comprometen todas las cuentas.
export class LocalSigner implements Signer {
  private readonly entryPoint = {
    address: config.ERC4337_ENTRYPOINT_ADDRESS as Address,
    version: "0.7" as const,
  };

  private ownerFor(personId: string) {
    const masterKey = config.LOCAL_SIGNER_MASTER_KEY!; // validado en config.ts cuando SIGNER_BACKEND=local
    const privKey = keccak256(toHex(`${masterKey}:${personId}`));
    return privateKeyToAccount(privKey);
  }

  private async accountFor(personId: string) {
    return toSimpleSmartAccount({
      client: publicClient,
      owner: this.ownerFor(personId),
      entryPoint: this.entryPoint,
    });
  }

  // signAndSendUserOp re-deriva el owner conociendo solo `from`. El caller
  // (payments.authorize) SIEMPRE llama getOrCreateAccount antes de firmar, así
  // que el mapa está caliente; firmar "en frío" por dirección fallaría.
  private readonly addressToPerson = new Map<string, string>();

  async getOrCreateAccount(personId: string): Promise<{ address: string }> {
    const account = await this.accountFor(personId);
    // Dirección contrafactual: el primer UserOp incluye el initCode que la despliega.
    this.addressToPerson.set(account.address.toLowerCase(), personId);
    return { address: account.address };
  }

  async signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    const account = await this.accountForAddress(params.from);

    const smartAccountClient = createSmartAccountClient({
      account,
      chain: arcChain,
      bundlerTransport: http(config.ERC4337_BUNDLER_URL),
    });

    const txHash = await smartAccountClient.sendTransaction({
      to: USDC_ADDRESS,
      data: encodeUsdcTransfer(params.to as Address, params.amountUsdc),
      value: 0n,
    });

    return { txHash };
  }

  private async accountForAddress(address: string) {
    const personId = this.addressToPerson.get(address.toLowerCase());
    if (!personId) {
      throw new Error(`no hay personId registrado para la cuenta ${address}`);
    }
    return this.accountFor(personId);
  }
}
