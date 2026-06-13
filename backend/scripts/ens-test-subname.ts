import {
  createPublicClient,
  createWalletClient,
  http,
  namehash,
  parseAbi,
  zeroAddress,
  type Address,
  type Hex,
} from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { sepolia } from "viem/chains";
import { config } from "../src/config.js";

// Validates ENSv2 subname issuance end-to-end against velt.eth's deployed registry + resolver:
// registry.register(label, owner, subregistry=0, resolver, roleBitmap=0, expiry) then
// resolver.setAddr(node, addr) and reads it back. Proves the flow before wiring registrar.ts.

const REGISTRY: Address = "0x7df8b8f316d5d4c1077612e2b32f93635bd40c0f";
const RESOLVER: Address = "0xb39AB6Eb190C055656ae07C52E3cD8c393FF4cE8";
const LABEL = "test1";

const registryAbi = parseAbi([
  "function register(string label, address owner, address registry, address resolver, uint256 roleBitmap, uint64 expiry) returns (uint256)",
  "function getResolver(string label) view returns (address)",
]);
const resolverAbi = parseAbi([
  "function setAddr(bytes32 node, address a)",
  "function addr(bytes32 node) view returns (address)",
]);

async function main(): Promise<void> {
  const account = privateKeyToAccount(config.ENS_OWNER_PRIVATE_KEY as Hex);
  const transport = http(config.SEPOLIA_RPC_URL);
  const publicClient = createPublicClient({ chain: sepolia, transport });
  const walletClient = createWalletClient({ account, chain: sepolia, transport });
  const parent = config.ENS_PARENT_NAME!;
  const fullName = `${LABEL}.${parent}`;
  const node = namehash(fullName);
  const expiry = BigInt(Math.floor(Date.now() / 1000) + 365 * 24 * 3600);

  console.log(`registrando ${fullName} (owner ${account.address})...`);
  try {
    const tx = await walletClient.writeContract({
      address: REGISTRY,
      abi: registryAbi,
      functionName: "register",
      args: [LABEL, account.address, zeroAddress, RESOLVER, 0n, expiry],
    });
    await publicClient.waitForTransactionReceipt({ hash: tx });
    console.log("✅ register OK:", tx);
  } catch (e) {
    console.log("register falló:", (e as Error).message.slice(0, 200));
  }

  const res = await publicClient.readContract({
    address: REGISTRY,
    abi: registryAbi,
    functionName: "getResolver",
    args: [LABEL],
  });
  console.log("getResolver(test1):", res);

  console.log(`setAddr(${fullName}) → ${account.address}...`);
  try {
    const tx2 = await walletClient.writeContract({
      address: RESOLVER,
      abi: resolverAbi,
      functionName: "setAddr",
      args: [node, account.address],
    });
    await publicClient.waitForTransactionReceipt({ hash: tx2 });
    console.log("✅ setAddr OK:", tx2);
  } catch (e) {
    console.log("setAddr falló:", (e as Error).message.slice(0, 200));
  }

  const resolved = await publicClient.readContract({
    address: RESOLVER,
    abi: resolverAbi,
    functionName: "addr",
    args: [node],
  });
  console.log(`addr(${fullName}):`, resolved, resolved.toLowerCase() === account.address.toLowerCase() ? "✅ RESUELVE" : "❌");
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
