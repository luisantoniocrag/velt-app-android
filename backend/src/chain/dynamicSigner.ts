import { DynamicEvmWalletClient } from "@dynamic-labs-wallet/node-evm";
import { ThresholdSignatureScheme } from "@dynamic-labs-wallet/core";
import type { Address } from "viem";
import { config } from "../config.js";
import { db, type DynamicWalletRow } from "../db/client.js";
import type { ChainCall, Signer } from "./signer.js";
import { arcChain, publicClient, USDC_ADDRESS, encodeUsdcTransfer } from "./usdc.js";

// Dynamic Server Wallets (TSS-MPC EOAs). Each subject (merchant:<id> or payer personId) maps
// to one wallet, persisted in `dynamic_wallets`. The MPC key material lives in the DB; the
// single private key never exists in one place. Being EOAs, these wallets need native gas on
// Arc to transact (funded out-of-band for the hackathon).
//
// SECURITY (hackathon): externalServerKeyShares are stored in the DB. Production should keep
// them in a secrets vault (HSM/KMS). See README.

interface WalletMaterial {
  address: string;
  walletMetadata: unknown;
  serverKeyShares: unknown;
}

export class DynamicSigner implements Signer {
  private client: DynamicEvmWalletClient | null = null;
  private readonly byAddress = new Map<string, WalletMaterial>();

  private async getClient(): Promise<DynamicEvmWalletClient> {
    if (this.client) return this.client;
    const client = new DynamicEvmWalletClient({
      environmentId: config.DYNAMIC_ENVIRONMENT_ID!,
    });
    await client.authenticateApiToken(config.DYNAMIC_API_TOKEN!);
    this.client = client;
    return client;
  }

  private cache(material: WalletMaterial): void {
    this.byAddress.set(material.address.toLowerCase(), material);
  }

  async getOrCreateAccount(subjectId: string): Promise<{ address: string }> {
    const { data: existing } = await db
      .from("dynamic_wallets")
      .select("*")
      .eq("subject", subjectId)
      .maybeSingle<DynamicWalletRow>();
    if (existing) {
      this.cache({
        address: existing.account_address,
        walletMetadata: existing.wallet_metadata,
        serverKeyShares: existing.server_key_shares,
      });
      return { address: existing.account_address };
    }

    const client = await this.getClient();
    const created = await client.createWalletAccount({
      thresholdSignatureScheme: ThresholdSignatureScheme.TWO_OF_TWO,
    });
    const address = created.walletMetadata.accountAddress;

    const { error } = await db.from("dynamic_wallets").insert({
      subject: subjectId,
      account_address: address,
      wallet_metadata: created.walletMetadata,
      server_key_shares: created.externalServerKeyShares,
    });
    // Race: another caller may have inserted the same subject first; re-read and reuse it.
    if (error) {
      const { data: retry } = await db
        .from("dynamic_wallets")
        .select("*")
        .eq("subject", subjectId)
        .maybeSingle<DynamicWalletRow>();
      if (retry) {
        this.cache({
          address: retry.account_address,
          walletMetadata: retry.wallet_metadata,
          serverKeyShares: retry.server_key_shares,
        });
        return { address: retry.account_address };
      }
      throw new Error(`no se pudo persistir la wallet Dynamic de ${subjectId}`);
    }

    this.cache({
      address,
      walletMetadata: created.walletMetadata,
      serverKeyShares: created.externalServerKeyShares,
    });
    // Manual gas funding: surface every new wallet so its Arc native balance can be topped up.
    console.log(`[dynamic] nueva wallet '${subjectId}' → ${address} (fondear gas nativo en Arc)`);
    return { address };
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

  // EOAs cannot batch: each call is a separate transaction sent in order. Returns the hash of
  // the LAST call (e.g. the escrow `hold` after its `approve`), matching the escrow flow's
  // expectation of a single settling tx hash.
  async signAndSendCalls(params: { from: string; calls: ChainCall[] }): Promise<{ txHash: string }> {
    const material = await this.materialFor(params.from);
    const client = await this.getClient();
    const walletClient = await client.getWalletClient({
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- SDK metadata is opaque JSON
      walletMetadata: material.walletMetadata as any,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- SDK key shares are opaque JSON
      externalServerKeyShares: material.serverKeyShares as any,
      chain: arcChain,
      chainId: arcChain.id,
      rpcUrl: config.ARC_RPC_URL,
    });

    let txHash = "";
    for (const call of params.calls) {
      txHash = await walletClient.sendTransaction({
        to: call.to as Address,
        data: call.data,
        value: call.value ?? 0n,
        account: walletClient.account,
        chain: arcChain,
      });
      await publicClient.waitForTransactionReceipt({ hash: txHash as `0x${string}` });
    }
    return { txHash };
  }

  private async materialFor(address: string): Promise<WalletMaterial> {
    const cached = this.byAddress.get(address.toLowerCase());
    if (cached) return cached;

    const { data } = await db
      .from("dynamic_wallets")
      .select("*")
      .ilike("account_address", address)
      .maybeSingle<DynamicWalletRow>();
    if (!data) throw new Error(`no hay wallet Dynamic registrada para ${address}`);

    const material: WalletMaterial = {
      address: data.account_address,
      walletMetadata: data.wallet_metadata,
      serverKeyShares: data.server_key_shares,
    };
    this.cache(material);
    return material;
  }
}
