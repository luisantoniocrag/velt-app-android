import { randomBytes } from "node:crypto";
import {
  createPublicClient,
  createWalletClient,
  http,
  namehash,
  parseAbi,
  type Address,
  type Hex,
} from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { sepolia } from "viem/chains";
import { config } from "../src/config.js";

// Registers the ENS parent name (ENS_PARENT_NAME, e.g. velt.eth) on the CANONICAL Sepolia ENS
// using the backend's ENS_OWNER EOA. The modern ETHRegistrarController wraps .eth 2LDs into the
// NameWrapper on registration, so the name comes out wrapped and owned by the EOA — exactly what
// the subname registrar (registrar.ts) needs. Commit → wait minCommitmentAge → register.
// Usage: npm run register:ens

// Production Sepolia ETHRegistrarController (authorized on the NameWrapper). The staging-branch
// deployment listed a newer ENSv2 controller (0xfb3c…) that is NOT a NameWrapper controller, so
// its register() reverts. This legacy controller uses flat args + bool reverseRecord + uint16 fuses.
const CONTROLLER: Address = "0xFED6a969AaA60E4961FCD3EBF1A2e8913ac65B72";
const NAME_WRAPPER: Address = "0x0635513f179D50A207757E05759CbD106d7dFcE8";
const PUBLIC_RESOLVER: Address = "0x8FADE66B79cC9f707aB26799354482EB93a5B7dD";
const REGISTRY: Address = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e";
const DURATION = 31_536_000n; // 1 year

const controllerAbi = parseAbi([
  "function available(string label) view returns (bool)",
  "function minCommitmentAge() view returns (uint256)",
  "function rentPrice(string label, uint256 duration) view returns ((uint256 base, uint256 premium))",
  "function makeCommitment(string name, address owner, uint256 duration, bytes32 secret, address resolver, bytes[] data, bool reverseRecord, uint16 ownerControlledFuses) pure returns (bytes32)",
  "function commit(bytes32 commitment)",
  "function register(string name, address owner, uint256 duration, bytes32 secret, address resolver, bytes[] data, bool reverseRecord, uint16 ownerControlledFuses) payable",
]);

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function main(): Promise<void> {
  const parent = config.ENS_PARENT_NAME;
  if (!parent || !parent.endsWith(".eth")) {
    throw new Error("ENS_PARENT_NAME debe ser un .eth de segundo nivel, p. ej. velt.eth");
  }
  if (!config.ENS_OWNER_PRIVATE_KEY || !config.SEPOLIA_RPC_URL) {
    throw new Error("Faltan ENS_OWNER_PRIVATE_KEY / SEPOLIA_RPC_URL en el .env");
  }
  const label = parent.replace(/\.eth$/, "");

  const account = privateKeyToAccount(config.ENS_OWNER_PRIVATE_KEY as Hex);
  const transport = http(config.SEPOLIA_RPC_URL);
  const publicClient = createPublicClient({ chain: sepolia, transport });
  const walletClient = createWalletClient({ account, chain: sepolia, transport });

  const registryOwner = await publicClient.readContract({
    address: REGISTRY,
    abi: parseAbi(["function owner(bytes32) view returns (address)"]),
    functionName: "owner",
    args: [namehash(parent)],
  });
  if (registryOwner === NAME_WRAPPER) {
    console.log(`[register-ens] ${parent} ya está registrado y wrapeado. Nada que hacer.`);
    return;
  }

  const available = await publicClient.readContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "available",
    args: [label],
  });
  if (!available) {
    throw new Error(`${parent} no está disponible y no está wrapeado bajo nuestro NameWrapper`);
  }

  const secret = `0x${randomBytes(32).toString("hex")}` as Hex;
  // Flat args shared verbatim by makeCommitment and register (any mismatch → commitment not found).
  const args = [
    label,
    account.address,
    DURATION,
    secret,
    PUBLIC_RESOLVER,
    [] as Hex[],
    false,
    0,
  ] as const;

  const commitment = await publicClient.readContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "makeCommitment",
    args,
  });

  console.log(`[register-ens] commit de ${parent} desde ${account.address}...`);
  const commitTx = await walletClient.writeContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "commit",
    args: [commitment],
  });
  await publicClient.waitForTransactionReceipt({ hash: commitTx });

  const minAge = await publicClient.readContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "minCommitmentAge",
  });
  const waitSeconds = Number(minAge) + 5;
  console.log(`[register-ens] commit confirmado. Esperando ${waitSeconds}s (minCommitmentAge)...`);
  await sleep(waitSeconds * 1000);

  const price = await publicClient.readContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "rentPrice",
    args: [label, DURATION],
  });
  const value = ((price.base + price.premium) * 110n) / 100n; // +10% buffer por fluctuación

  console.log(`[register-ens] register de ${parent} (value ${value} wei)...`);
  const registerTx = await walletClient.writeContract({
    address: CONTROLLER,
    abi: controllerAbi,
    functionName: "register",
    args,
    value,
  });
  const receipt = await publicClient.waitForTransactionReceipt({ hash: registerTx });
  if (receipt.status !== "success") throw new Error(`register revertido (tx ${registerTx})`);

  const finalOwner = await publicClient.readContract({
    address: REGISTRY,
    abi: parseAbi(["function owner(bytes32) view returns (address)"]),
    functionName: "owner",
    args: [namehash(parent)],
  });
  console.log(`[register-ens] ✅ ${parent} registrado. registry.owner = ${finalOwner}`);
  console.log(`[register-ens] wrapeado: ${finalOwner === NAME_WRAPPER}`);
  console.log(`[register-ens] dueño efectivo (EOA backend): ${account.address}`);
  console.log("[register-ens] los subnames ya pueden registrarse vía registrar.ts");
}

main().catch((err) => {
  console.error("[register-ens] fallo:", err);
  process.exit(1);
});
