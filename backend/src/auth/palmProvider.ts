import { z } from "zod";
import type { AuthContext, AuthIdentity, AuthProvider } from "./provider.js";
import { identifyPalm } from "./bioserver.js";

const credentialsSchema = z.object({
  template: z.string().min(1, "template (base64) requerido"),
});

// Autentica por palma: verifica el template contra el bioserver y usa el personId como externalId.
export class PalmAuthProvider implements AuthProvider {
  readonly name = "palm";

  async authenticate(credentials: unknown, ctx: AuthContext): Promise<AuthIdentity> {
    const parsed = credentialsSchema.safeParse(credentials);
    if (!parsed.success) {
      throw new Error(parsed.error.issues.map((i) => i.message).join("; "));
    }

    const personId = await identifyPalm(parsed.data.template, ctx.log);
    return { provider: this.name, externalId: personId };
  }
}
