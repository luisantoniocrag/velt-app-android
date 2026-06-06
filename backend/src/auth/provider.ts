// Capa de autenticación desacoplada del resto del backend, igual que la interfaz `Signer`.
// Un proveedor traduce unas credenciales (palma, Google, ...) en una identidad estable. El
// mapeo identidad → comerciante vive en la tabla `merchant_identities`.
export interface AuthIdentity {
  provider: string; // 'palm', 'google', ...
  externalId: string; // personId (palma), sub (google), ...
}

export interface AuthContext {
  log: { info(obj: unknown, msg?: string): void; warn(obj: unknown, msg?: string): void };
}

export interface AuthProvider {
  readonly name: string;
  authenticate(credentials: unknown, ctx: AuthContext): Promise<AuthIdentity>;
}

export type ProviderName = "palm" | "google" | "email";

const cache = new Map<string, AuthProvider>();

export async function getAuthProvider(name: string): Promise<AuthProvider> {
  const cached = cache.get(name);
  if (cached) return cached;

  let provider: AuthProvider;
  switch (name) {
    case "palm": {
      const { PalmAuthProvider } = await import("./palmProvider.js");
      provider = new PalmAuthProvider();
      break;
    }
    // Stubs del camino futuro: enchufar sin tocar login/register ni el modelo de datos.
    case "google":
    case "email":
      throw new Error(`auth provider '${name}' not implemented`);
    default:
      throw new Error(`auth provider desconocido: '${name}'`);
  }

  cache.set(name, provider);
  return provider;
}
