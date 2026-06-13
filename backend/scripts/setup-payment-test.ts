import { db } from "../src/db/client.js";
import { createMerchant } from "../src/routes/merchants.js";
import { getSigner, OPERATOR_SUBJECT } from "../src/chain/signer.js";
import { getUsdcBalance, publicClient } from "../src/chain/usdc.js";
import { formatEther, formatUnits, type Address } from "viem";

// Sets up an end-to-end payment test with the Dynamic signer: a test user + merchant (with its
// Dynamic wallet) and a payer wallet derived from a test personId. Prints the addresses that need
// manual gas/USDC funding on Arc. Idempotent-ish: reuses an existing test merchant if present.
const TEST_PERSON_ID = "demo-payer-001";
const log = { info() {}, warn() {}, error() {} } as never;

async function main(): Promise<void> {
  let { data: user } = await db.from("users").select("id").limit(1).maybeSingle<{ id: string }>();
  if (!user) {
    const inserted = await db.from("users").insert({}).select().single<{ id: string }>();
    user = inserted.data;
  }

  let { data: merchant } = await db
    .from("merchants")
    .select("id, name, smart_account_address")
    .is("deleted_at", null)
    .limit(1)
    .maybeSingle<{ id: string; name: string; smart_account_address: string }>();
  if (!merchant) {
    const created = await createMerchant({ name: "Demo Cafe", ownerUserId: user!.id, log });
    merchant = { id: created.id, name: created.name, smart_account_address: created.smart_account_address };
  }

  const signer = await getSigner();
  const { address: payer } = await signer.getOrCreateAccount(TEST_PERSON_ID);
  const { address: operator } = await signer.getOrCreateAccount(OPERATOR_SUBJECT);

  const gas = async (a: string) => formatEther(await publicClient.getBalance({ address: a as Address }));
  const usdc = async (a: string) => formatUnits(await getUsdcBalance(a as Address), 6);

  console.log("\n========= ESCENARIO DE PAGO (Dynamic / Arc) =========");
  console.log(`merchant:  ${merchant!.id}  "${merchant!.name}"`);
  console.log(`  wallet:  ${merchant!.smart_account_address}  (recibe; no requiere fondeo)`);
  console.log(`\nPAGADOR personId='${TEST_PERSON_ID}'`);
  console.log(`  wallet:  ${payer}`);
  console.log(`  → FONDEAR: USDC (para pagar) + gas nativo en Arc (para firmar)`);
  console.log(`  saldo actual → USDC: ${await usdc(payer)} | gas: ${await gas(payer)}`);
  console.log(`\nOPERATOR (libera el escrow)`);
  console.log(`  wallet:  ${operator}`);
  console.log(`  → FONDEAR: gas nativo en Arc`);
  console.log(`  saldo actual → gas: ${await gas(operator)}`);
  console.log("\nDespués de fondear, autoriza un pago con:");
  console.log(`  merchantId=${merchant!.id}  personId=${TEST_PERSON_ID}`);
  console.log("=====================================================\n");
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error("[setup-payment-test] fallo:", err);
    process.exit(1);
  });
