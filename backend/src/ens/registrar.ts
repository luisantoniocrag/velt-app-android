import type { FastifyBaseLogger } from "fastify";
import {
  createPublicClient,
  createWalletClient,
  http,
  keccak256,
  namehash,
  parseAbi,
  toHex,
  zeroAddress,
  type Address,
  type Hex,
} from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { sepolia } from "viem/chains";
import { config } from "../config.js";
import { db, type MerchantRow, type VeltUserRow } from "../db/client.js";

// Canonical ENS deployments on Sepolia.
const NAME_WRAPPER: Address = "0x0635513f179D50A207757E05759CbD106d7dFcE8";
const PUBLIC_RESOLVER: Address = "0x8FADE66B79cC9f707aB26799354482EB93a5B7dD";

const nameWrapperAbi = parseAbi([
  "function setSubnodeRecord(bytes32 parentNode, string label, address owner, address resolver, uint64 ttl, uint32 fuses, uint64 expiry) returns (bytes32)",
  "function ownerOf(uint256 id) view returns (address)",
]);

const resolverAbi = parseAbi(["function setAddr(bytes32 node, address a)"]);

export const ensEnabled = (): boolean =>
  Boolean(config.ENS_PARENT_NAME && config.ENS_OWNER_PRIVATE_KEY && config.SEPOLIA_RPC_URL);

function clients() {
  const account = privateKeyToAccount(config.ENS_OWNER_PRIVATE_KEY as Hex);
  const transport = http(config.SEPOLIA_RPC_URL);
  return {
    account,
    publicClient: createPublicClient({ chain: sepolia, transport }),
    walletClient: createWalletClient({ account, chain: sepolia, transport }),
  };
}

export function slugify(name: string): string {
  return name
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

// Registrations run through an in-process queue: every tx is signed by the same EOA, and
// concurrent fire-and-forget calls would otherwise race on its nonce. Single-instance, v1 norm.
let queue: Promise<unknown> = Promise.resolve();
function enqueue<T>(task: () => Promise<T>): Promise<T> {
  const next = queue.then(task, task);
  queue = next.catch(() => undefined);
  return next;
}

// DECISION: the subname stays owned by the backend's ENS EOA (not by the actor's smart
// account) so the same EOA can set the addr record; forward resolution to the actor's
// address is what the demo needs.
async function registerSubnameOnChain(label: string, owner: Address): Promise<string> {
  const parent = config.ENS_PARENT_NAME!;
  const { account, publicClient, walletClient } = clients();

  let candidate = label;
  for (let attempt = 2; ; attempt++) {
    const taken = await publicClient.readContract({
      address: NAME_WRAPPER,
      abi: nameWrapperAbi,
      functionName: "ownerOf",
      args: [BigInt(namehash(`${candidate}.${parent}`))],
    });
    if (taken === zeroAddress) break;
    candidate = `${label}-${attempt}`;
  }
  const fullName = `${candidate}.${parent}`;

  const holdTx = await walletClient.writeContract({
    address: NAME_WRAPPER,
    abi: nameWrapperAbi,
    functionName: "setSubnodeRecord",
    args: [namehash(parent), candidate, account.address, PUBLIC_RESOLVER, 0n, 0, 0n],
  });
  await publicClient.waitForTransactionReceipt({ hash: holdTx });

  const addrTx = await walletClient.writeContract({
    address: PUBLIC_RESOLVER,
    abi: resolverAbi,
    functionName: "setAddr",
    args: [namehash(fullName), owner],
  });
  await publicClient.waitForTransactionReceipt({ hash: addrTx });

  return fullName;
}

export function registerSubname(label: string, owner: Address): Promise<string> {
  return enqueue(() => registerSubnameOnChain(label, owner));
}

// Fire-and-forget wrappers: never throw, never block the main flow. Callers may fire on
// every fetch as a backfill for rows without ens_name, so an in-flight guard prevents the
// same actor from registering twice while its first registration is still running.
const inFlight = new Set<string>();

export async function registerMerchantEns(
  merchant: Pick<MerchantRow, "id" | "name" | "smart_account_address">,
  log: FastifyBaseLogger,
): Promise<void> {
  if (!ensEnabled() || inFlight.has(merchant.id)) return;
  inFlight.add(merchant.id);
  try {
    const ensName = await registerSubname(
      slugify(merchant.name),
      merchant.smart_account_address as Address,
    );
    await db.from("merchants").update({ ens_name: ensName }).eq("id", merchant.id);
    log.info({ merchantId: merchant.id, ensName }, "subname ENS registrado");
  } catch (err) {
    log.warn({ err, merchantId: merchant.id }, "fallo al registrar subname ENS del comercio");
  } finally {
    inFlight.delete(merchant.id);
  }
}

export async function registerPayerEns(
  user: Pick<VeltUserRow, "id" | "person_id" | "smart_account_address">,
  log: FastifyBaseLogger,
): Promise<void> {
  if (!ensEnabled() || inFlight.has(user.id)) return;
  inFlight.add(user.id);
  try {
    const label = `palm-${keccak256(toHex(user.person_id)).slice(2, 8)}`;
    const ensName = await registerSubname(label, user.smart_account_address as Address);
    await db.from("velt_users").update({ ens_name: ensName }).eq("id", user.id);
    log.info({ veltUserId: user.id, ensName }, "subname ENS registrado");
  } catch (err) {
    log.warn({ err, veltUserId: user.id }, "fallo al registrar subname ENS del pagador");
  } finally {
    inFlight.delete(user.id);
  }
}
