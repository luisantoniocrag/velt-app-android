import {
  createPublicClient,
  defineChain,
  encodeFunctionData,
  erc20Abi,
  http,
  type Address,
} from "viem";
import { config } from "../config.js";

/**
 * Definición de la red Arc (Circle) a partir del entorno, y helpers USDC.
 */
export const arcChain = defineChain({
  id: config.ARC_CHAIN_ID,
  name: "Arc Testnet",
  nativeCurrency: { name: "Ether", symbol: "ETH", decimals: 18 },
  rpcUrls: {
    default: { http: [config.ARC_RPC_URL] },
  },
});

/** Cliente público (lecturas RPC: recibos, saldos, etc.). */
export const publicClient = createPublicClient({
  chain: arcChain,
  transport: http(config.ARC_RPC_URL),
});

export const USDC_ADDRESS = config.USDC_CONTRACT_ADDRESS as Address;

/** USDC usa 6 decimales. */
export const USDC_DECIMALS = 6;

/** Calldata de `transfer(to, amount)` del contrato USDC. */
export function encodeUsdcTransfer(to: Address, amountUsdc: bigint): `0x${string}` {
  return encodeFunctionData({
    abi: erc20Abi,
    functionName: "transfer",
    args: [to, amountUsdc],
  });
}
