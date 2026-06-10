import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { createWalletClient, http, type Abi, type Hex } from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { config } from "../src/config.js";
import { arcChain, publicClient, USDC_ADDRESS } from "../src/chain/usdc.js";
import { getSigner, OPERATOR_SUBJECT } from "../src/chain/signer.js";

// Compiles contracts/VeltEscrow.sol with the solc npm package (no Foundry on this machine)
// and deploys it to Arc from a funded EOA (DEPLOYER_PRIVATE_KEY env var).
// Usage: DEPLOYER_PRIVATE_KEY=0x... npm run deploy:escrow

const require = createRequire(import.meta.url);
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- solc ships without types
const solc: any = require("solc");

function compile(): { abi: Abi; bytecode: Hex } {
  const source = readFileSync(new URL("../contracts/VeltEscrow.sol", import.meta.url), "utf8");
  const input = {
    language: "Solidity",
    sources: { "VeltEscrow.sol": { content: source } },
    settings: {
      optimizer: { enabled: true, runs: 200 },
      outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } },
    },
  };

  const output = JSON.parse(solc.compile(JSON.stringify(input)));
  const errors = (output.errors ?? []).filter((e: { severity: string }) => e.severity === "error");
  if (errors.length > 0) {
    throw new Error(errors.map((e: { formattedMessage: string }) => e.formattedMessage).join("\n"));
  }

  const artifact = output.contracts["VeltEscrow.sol"].VeltEscrow;
  return { abi: artifact.abi as Abi, bytecode: `0x${artifact.evm.bytecode.object}` as Hex };
}

async function main(): Promise<void> {
  const deployerKey = process.env.DEPLOYER_PRIVATE_KEY;
  if (!deployerKey) {
    console.error("[deploy-escrow] falta DEPLOYER_PRIVATE_KEY (EOA con gas en Arc)");
    process.exit(1);
  }

  const { abi, bytecode } = compile();
  console.log("[deploy-escrow] contrato compilado (solc)");

  const signer = await getSigner();
  const { address: operator } = await signer.getOrCreateAccount(OPERATOR_SUBJECT);
  console.log(`[deploy-escrow] operator (smart account '${OPERATOR_SUBJECT}'): ${operator}`);

  const deployer = privateKeyToAccount(deployerKey as Hex);
  const wallet = createWalletClient({
    account: deployer,
    chain: arcChain,
    transport: http(config.ARC_RPC_URL),
  });

  console.log(`[deploy-escrow] desplegando desde ${deployer.address}...`);
  const hash = await wallet.deployContract({
    abi,
    bytecode,
    args: [USDC_ADDRESS, operator, BigInt(config.ESCROW_RELEASE_DELAY_SECONDS)],
  });
  const receipt = await publicClient.waitForTransactionReceipt({ hash });

  if (receipt.status !== "success" || !receipt.contractAddress) {
    throw new Error(`deploy revertido (tx ${hash})`);
  }

  console.log(`[deploy-escrow] VeltEscrow desplegado: ${receipt.contractAddress}`);
  console.log(`[deploy-escrow] releaseDelaySeconds: ${config.ESCROW_RELEASE_DELAY_SECONDS}`);
  console.log("[deploy-escrow] siguiente paso:");
  console.log(`  1. ESCROW_CONTRACT_ADDRESS=${receipt.contractAddress} en el .env`);
  console.log(`  2. fondear con gas la cuenta del operator (${operator}) para que pueda firmar releases`);
}

main().catch((err) => {
  console.error("[deploy-escrow] fallo:", err);
  process.exit(1);
});
