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

// ENSv2 (Sepolia): the parent name (velt.eth) has its own deployed registry; subnames are
// issued via registry.register(...) and resolved via its PermissionedResolver.setAddr(...).
const registryAbi = parseAbi([
  "function register(string label, address owner, address registry, address resolver, uint256 roleBitmap, uint64 expiry) returns (uint256)",
  "function getResolver(string label) view returns (address)",
]);

const resolverAbi = parseAbi(["function setAddr(bytes32 node, address a)"]);

// Subnames don't expire for the demo horizon; capped well within the parent's registration.
const SUBNAME_DURATION_SECONDS = 365 * 24 * 3600;

export const ensEnabled = (): boolean =>
  Boolean(
    config.ENS_PARENT_NAME &&
      config.ENS_OWNER_PRIVATE_KEY &&
      config.SEPOLIA_RPC_URL &&
      config.ENS_V2_REGISTRY_ADDRESS &&
      config.ENS_V2_RESOLVER_ADDRESS,
  );

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

// DECISION: the subname is owned by the backend's ENS EOA (which controls the parent registry)
// so it can set the addr record; the record points forward to the actor's wallet, which is what
// the demo needs. Collisions get a `-N` suffix (getResolver != 0 means the label is taken).
async function registerSubnameOnChain(label: string, owner: Address): Promise<string> {
  const parent = config.ENS_PARENT_NAME!;
  const registry = config.ENS_V2_REGISTRY_ADDRESS as Address;
  const resolver = config.ENS_V2_RESOLVER_ADDRESS as Address;
  const { account, publicClient, walletClient } = clients();

  let candidate = label;
  for (let attempt = 2; ; attempt++) {
    const existing = await publicClient.readContract({
      address: registry,
      abi: registryAbi,
      functionName: "getResolver",
      args: [candidate],
    });
    if (existing === zeroAddress) break;
    candidate = `${label}-${attempt}`;
  }
  const fullName = `${candidate}.${parent}`;
  const expiry = BigInt(Math.floor(Date.now() / 1000) + SUBNAME_DURATION_SECONDS);

  const registerTx = await walletClient.writeContract({
    address: registry,
    abi: registryAbi,
    functionName: "register",
    args: [candidate, account.address, zeroAddress, resolver, 0n, expiry],
  });
  await publicClient.waitForTransactionReceipt({ hash: registerTx });

  const addrTx = await walletClient.writeContract({
    address: resolver,
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
