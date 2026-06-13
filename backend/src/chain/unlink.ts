import { Buffer } from "node:buffer";
import { DynamicEvmWalletClient } from "@dynamic-labs-wallet/node-evm";
import { account as unlinkAccount, createUnlinkClient, evm } from "@unlink-xyz/sdk/client";
import { createUnlinkAdmin } from "@unlink-xyz/sdk/admin";
import { createPublicClient, http, keccak256, parseUnits, toHex } from "viem";
import { config } from "../config.js";
import { db, type DynamicWalletRow } from "../db/client.js";
import { subjectForMerchant } from "./signer.js";
import { arcChain, USDC_ADDRESS, USDC_DECIMALS } from "./usdc.js";

export const unlinkEnabled = (): boolean => Boolean(config.UNLINK_API_KEY);

// Deterministic shielded-pool account per merchant. SECURITY (hackathon): derived from the
// merchantId; production should derive from a vault secret so the seed isn't guessable.
function unlinkSeedFor(merchantId: string): Uint8Array {
  const hex = keccak256(toHex(`velt-unlink:${merchantId}`)).slice(2);
  return Uint8Array.from(Buffer.from(hex, "hex"));
}

// Rehydrates the merchant's Dynamic wallet as a viem WalletClient on Arc, so Unlink can use it
// as the EVM provider that signs the Permit2 deposit into the pool.
async function merchantWalletClient(subject: string) {
  const { data } = await db
    .from("dynamic_wallets")
    .select("*")
    .eq("subject", subject)
    .maybeSingle<DynamicWalletRow>();
  if (!data) throw new Error(`no hay wallet Dynamic para ${subject}`);

  const client = new DynamicEvmWalletClient({ environmentId: config.DYNAMIC_ENVIRONMENT_ID! });
  await client.authenticateApiToken(config.DYNAMIC_API_TOKEN!);
  return client.getWalletClient({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- SDK metadata is opaque JSON
    walletMetadata: data.wallet_metadata as any,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- SDK key shares are opaque JSON
    externalServerKeyShares: data.server_key_shares as any,
    chain: arcChain,
    chainId: arcChain.id,
    rpcUrl: config.ARC_RPC_URL,
  });
}

// Private withdrawal: the merchant's real USDC is deposited into the Unlink shielded pool (signed
// by its Dynamic wallet) and then withdrawn privately to `to`, breaking the on-chain link between
// the merchant account and the destination. Runs on arc-testnet.
export async function privateWithdraw(args: {
  merchantId: string;
  to: string;
  amount: string | number;
}): Promise<{ txHash: string }> {
  const subject = subjectForMerchant(args.merchantId);
  const walletClient = await merchantWalletClient(subject);
  const publicClient = createPublicClient({ chain: arcChain, transport: http(config.ARC_RPC_URL) });

  const admin = createUnlinkAdmin({
    environment: config.UNLINK_ENVIRONMENT,
    apiKey: config.UNLINK_API_KEY!,
  });
  const account = unlinkAccount.fromSeed({ seed: unlinkSeedFor(args.merchantId) });
  const unlinkAddress = await account.getAddress();

  const client = createUnlinkClient({
    environment: config.UNLINK_ENVIRONMENT,
    account,
    register: (payload) => admin.users.register(payload),
    authorizationToken: { provider: () => admin.authorizationTokens.issue({ unlinkAddress }) },
  });
  await client.ensureRegistered();

  const amount = parseUnits(String(args.amount), USDC_DECIMALS).toString();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- bridge viem versions across SDKs
  const evmProvider = evm.fromViem({ walletClient: walletClient as any, publicClient: publicClient as any });

  const deposit = await client.depositWithApproval({ token: USDC_ADDRESS, amount, evm: evmProvider });
  await deposit.wait();

  const withdrawal = await client.withdraw({ recipientEvmAddress: args.to, token: USDC_ADDRESS, amount });
  const confirmed = await withdrawal.wait();
  return { txHash: confirmed.txHash ?? withdrawal.txHash ?? "" };
}
