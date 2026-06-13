import "dotenv/config";
import { z } from "zod";

const SignerBackend = z.enum(["local", "privy", "turnkey", "dynamic"]);

const baseSchema = z.object({
  PORT: z.coerce.number().int().positive().default(3000),

  SUPABASE_URL: z.string().url(),
  SUPABASE_SERVICE_KEY: z.string().min(1),

  ARC_RPC_URL: z.string().url(),
  ARC_CHAIN_ID: z.coerce.number().int().positive(),
  USDC_CONTRACT_ADDRESS: z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida"),
  ERC4337_BUNDLER_URL: z.string().url(),
  ERC4337_ENTRYPOINT_ADDRESS: z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida"),

  // Conditional escrow (contracts/VeltEscrow.sol). Address is known only after deploying
  // (scripts/deploy-escrow.ts), so it stays optional: settlement throws if missing, boot does not.
  ESCROW_CONTRACT_ADDRESS: z
    .string()
    .regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida")
    .optional(),
  ESCROW_RELEASE_DELAY_SECONDS: z.coerce.number().int().positive().default(300),

  SIGNER_BACKEND: SignerBackend.default("local"),
  LOCAL_SIGNER_MASTER_KEY: z.string().optional(),
  PRIVY_APP_ID: z.string().optional(),
  PRIVY_APP_SECRET: z.string().optional(),
  TURNKEY_API_KEY: z.string().optional(),

  // Dynamic Server Wallets (MPC EOAs). Required when SIGNER_BACKEND=dynamic.
  DYNAMIC_ENVIRONMENT_ID: z.string().optional(),
  DYNAMIC_API_TOKEN: z.string().optional(),

  // Auth: JWT de acceso (firma HS256) + ventanas de vida.
  JWT_SECRET: z.string().min(32, "JWT_SECRET debe tener al menos 32 caracteres"),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(900), // 15 min
  REFRESH_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(2_592_000), // 30 días

  // Bioserver (proveedor de auth por palma). Mismas credenciales que VeltSensorConfig (Android).
  BIOSERVER_URL: z.string().url().default("https://openpalm.io/admin-app/"),
  BIOSERVER_CLIENT_ID: z.string().min(1),
  BIOSERVER_SHARED_SECRET: z.string().min(1),

  // ENS subnames on Sepolia (NameWrapper). All optional: if any is missing the feature is
  // disabled with a boot warning, never blocking the core payment flow.
  ENS_PARENT_NAME: z.string().min(1).optional(),
  ENS_OWNER_PRIVATE_KEY: z
    .string()
    .regex(/^0x[a-fA-F0-9]{64}$/, "llave privada 0x inválida")
    .optional(),
  SEPOLIA_RPC_URL: z.string().url().optional(),

  // Blink deposit SDK (payer funding on Base). Optional: without them the Blink routes
  // answer 503 blink_not_configured and the rest of the server works normally.
  BLINK_MERCHANT_ID: z.string().min(1).optional(),
  BLINK_MERCHANT_PRIVATE_KEY: z.string().min(1).optional(),

  // Stytch (login por teléfono — OTP por SMS/WhatsApp). STYTCH_ENV=test usa números sandbox.
  STYTCH_ENV: z.enum(["test", "live"]).default("test"),
  STYTCH_PROJECT_ID: z.string().min(1).optional(),
  STYTCH_SECRET: z.string().min(1).optional(),
});

const schema = baseSchema.superRefine((cfg, ctx) => {
  if (cfg.SIGNER_BACKEND === "local" && !cfg.LOCAL_SIGNER_MASTER_KEY) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["LOCAL_SIGNER_MASTER_KEY"],
      message: "requerida cuando SIGNER_BACKEND=local",
    });
  }
  if (cfg.SIGNER_BACKEND === "dynamic" && (!cfg.DYNAMIC_ENVIRONMENT_ID || !cfg.DYNAMIC_API_TOKEN)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["DYNAMIC_API_TOKEN"],
      message: "DYNAMIC_ENVIRONMENT_ID y DYNAMIC_API_TOKEN requeridos cuando SIGNER_BACKEND=dynamic",
    });
  }
});

export type AppConfig = z.infer<typeof baseSchema>;

function load(): AppConfig {
  const parsed = schema.safeParse(process.env);
  if (!parsed.success) {
    // Solo nombres de campos + mensajes; jamás valores (podrían ser secretos).
    const issues = parsed.error.issues
      .map((i) => `  - ${i.path.join(".") || "(root)"}: ${i.message}`)
      .join("\n");
    console.error(`[config] Configuración de entorno inválida:\n${issues}`);
    process.exit(1);
  }
  return parsed.data;
}

export const config = load();
