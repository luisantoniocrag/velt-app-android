import {
  createPublicClient,
  defineChain,
  encodeFunctionData,
  erc20Abi,
  http,
  type Address,
} from "viem";
import { config } from "../config.js";

export const arcChain = defineChain({
  id: config.ARC_CHAIN_ID,
  name: "Arc Testnet",
  nativeCurrency: { name: "Ether", symbol: "ETH", decimals: 18 },
  rpcUrls: {
    default: { http: [config.ARC_RPC_URL] },
  },
});

export const publicClient = createPublicClient({
  chain: arcChain,
  transport: http(config.ARC_RPC_URL),
});

export const USDC_ADDRESS = config.USDC_CONTRACT_ADDRESS as Address;

export const USDC_DECIMALS = 6;

export function encodeUsdcTransfer(to: Address, amountUsdc: bigint): `0x${string}` {
  return encodeFunctionData({
    abi: erc20Abi,
    functionName: "transfer",
    args: [to, amountUsdc],
  });
}

export function encodeUsdcApprove(spender: Address, amountUsdc: bigint): `0x${string}` {
  return encodeFunctionData({
    abi: erc20Abi,
    functionName: "approve",
    args: [spender, amountUsdc],
  });
}

export function getUsdcBalance(address: Address): Promise<bigint> {
  return publicClient.readContract({
    abi: erc20Abi,
    address: USDC_ADDRESS,
    functionName: "balanceOf",
    args: [address],
  });
}
