import { encodeFunctionData, keccak256, parseAbi, toHex, type Address } from "viem";
import { config } from "../config.js";

export const escrowAbi = parseAbi([
  "function hold(bytes32 paymentId, address merchant, uint256 amount)",
  "function release(bytes32 paymentId)",
  "function refund(bytes32 paymentId)",
]);

export function escrowAddress(): Address {
  if (!config.ESCROW_CONTRACT_ADDRESS) {
    throw new Error(
      "ESCROW_CONTRACT_ADDRESS no configurada: despliega contracts/VeltEscrow.sol (npm run deploy:escrow)",
    );
  }
  return config.ESCROW_CONTRACT_ADDRESS as Address;
}

// On-chain key for a payment: keccak256 of the UUID string bytes (bytes32 fits any UUID).
export const paymentIdHash = (paymentId: string): `0x${string}` => keccak256(toHex(paymentId));

export function encodeEscrowHold(
  paymentId: string,
  merchant: Address,
  amountUsdc: bigint,
): `0x${string}` {
  return encodeFunctionData({
    abi: escrowAbi,
    functionName: "hold",
    args: [paymentIdHash(paymentId), merchant, amountUsdc],
  });
}

export function encodeEscrowRelease(paymentId: string): `0x${string}` {
  return encodeFunctionData({
    abi: escrowAbi,
    functionName: "release",
    args: [paymentIdHash(paymentId)],
  });
}
