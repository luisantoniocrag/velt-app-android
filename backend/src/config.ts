import "dotenv/config";
import { z } from "zod";

const SignerBackend = z.enum(["local", "privy", "turnkey"]);

const baseSchema = z.object({
  PORT: z.coerce.number().int().positive().default(3000),

  SUPABASE_URL: z.string().url(),
  SUPABASE_SERVICE_KEY: z.string().min(1),

  ARC_RPC_URL: z.string().url(),
  ARC_CHAIN_ID: z.coerce.number().int().positive(),
  USDC_CONTRACT_ADDRESS: z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida"),
  ERC4337_BUNDLER_URL: z.string().url(),
  ERC4337_ENTRYPOINT_ADDRESS: z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida"),

  SIGNER_BACKEND: SignerBackend.default("local"),
  LOCAL_SIGNER_MASTER_KEY: z.string().optional(),
  PRIVY_APP_ID: z.string().optional(),
  PRIVY_APP_SECRET: z.string().optional(),
  TURNKEY_API_KEY: z.string().optional(),

  // Auth: JWT de acceso (firma HS256) + ventanas de vida.
  JWT_SECRET: z.string().min(32, "JWT_SECRET debe tener al menos 32 caracteres"),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(900), // 15 min
  REFRESH_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(2_592_000), // 30 días

  // Bioserver (proveedor de auth por palma). Mismas credenciales que VeltSensorConfig (Android).
  BIOSERVER_URL: z.string().url().default("https://openpalm.io/admin-app/"),
  BIOSERVER_CLIENT_ID: z.string().min(1),
  BIOSERVER_SHARED_SECRET: z.string().min(1),
});

const schema = baseSchema.superRefine((cfg, ctx) => {
  if (cfg.SIGNER_BACKEND === "local" && !cfg.LOCAL_SIGNER_MASTER_KEY) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["LOCAL_SIGNER_MASTER_KEY"],
      message: "requerida cuando SIGNER_BACKEND=local",
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
