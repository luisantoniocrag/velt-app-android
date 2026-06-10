import { createSmartAccountClient } from "permissionless";
import { toSimpleSmartAccount } from "permissionless/accounts";
import { privateKeyToAccount } from "viem/accounts";
import { http, keccak256, toHex, type Address } from "viem";
import { config } from "../config.js";
import type { ChainCall, Signer } from "./signer.js";
import { arcChain, publicClient, USDC_ADDRESS, encodeUsdcTransfer } from "./usdc.js";

// Owner determinista por subject: ownerPrivKey = keccak256("<masterKey>:<subjectId>").
// Riesgo: si se filtra LOCAL_SIGNER_MASTER_KEY, se comprometen todas las cuentas.
export class LocalSigner implements Signer {
  private readonly entryPoint = {
    address: config.ERC4337_ENTRYPOINT_ADDRESS as Address,
    version: "0.7" as const,
  };

  private ownerFor(subjectId: string) {
    const masterKey = config.LOCAL_SIGNER_MASTER_KEY!; // validado en config.ts cuando SIGNER_BACKEND=local
    const privKey = keccak256(toHex(`${masterKey}:${subjectId}`));
    return privateKeyToAccount(privKey);
  }

  private async accountFor(subjectId: string) {
    return toSimpleSmartAccount({
      client: publicClient,
      owner: this.ownerFor(subjectId),
      entryPoint: this.entryPoint,
    });
  }

  // signAndSendUserOp re-deriva el owner conociendo solo `from`. El caller (settlePayment
  // o processWithdrawal) SIEMPRE llama getOrCreateAccount antes de firmar, así que el mapa
  // está caliente; firmar "en frío" por dirección fallaría.
  private readonly addressToSubject = new Map<string, string>();

  async getOrCreateAccount(subjectId: string): Promise<{ address: string }> {
    const account = await this.accountFor(subjectId);
    // Dirección contrafactual: el primer UserOp incluye el initCode que la despliega.
    this.addressToSubject.set(account.address.toLowerCase(), subjectId);
    return { address: account.address };
  }

  async signAndSendUserOp(params: {
    from: string;
    to: string;
    amountUsdc: bigint;
  }): Promise<{ txHash: string }> {
    return this.signAndSendCalls({
      from: params.from,
      calls: [{ to: USDC_ADDRESS, data: encodeUsdcTransfer(params.to as Address, params.amountUsdc) }],
    });
  }

  async signAndSendCalls(params: { from: string; calls: ChainCall[] }): Promise<{ txHash: string }> {
    const account = await this.accountForAddress(params.from);

    const smartAccountClient = createSmartAccountClient({
      account,
      chain: arcChain,
      bundlerTransport: http(config.ERC4337_BUNDLER_URL),
    });

    const userOpHash = await smartAccountClient.sendUserOperation({
      calls: params.calls.map((call) => ({
        to: call.to as Address,
        data: call.data,
        value: call.value ?? 0n,
      })),
    });
    const receipt = await smartAccountClient.waitForUserOperationReceipt({ hash: userOpHash });

    return { txHash: receipt.receipt.transactionHash };
  }

  private async accountForAddress(address: string) {
    const subjectId = this.addressToSubject.get(address.toLowerCase());
    if (!subjectId) {
      throw new Error(`no hay subject registrado para la cuenta ${address}`);
    }
    return this.accountFor(subjectId);
  }
}
