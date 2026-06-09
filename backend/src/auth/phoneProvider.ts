import { z } from "zod";
import type { AuthContext, AuthIdentity, AuthProvider } from "./provider.js";
import { normalizePhone, verifyPhoneOtp } from "./stytchPhone.js";

const credentialsSchema = z.object({
  phone: z.string().min(1, "phone requerido"),
  code: z.string().min(4, "código OTP requerido"),
});

// Autentica por teléfono: verifica el OTP contra Supabase Auth y usa el número E.164 como externalId.
export class PhoneAuthProvider implements AuthProvider {
  readonly name = "phone";

  async authenticate(credentials: unknown, _ctx: AuthContext): Promise<AuthIdentity> {
    const parsed = credentialsSchema.safeParse(credentials);
    if (!parsed.success) {
      throw new Error(parsed.error.issues.map((i) => i.message).join("; "));
    }

    const phone = normalizePhone(parsed.data.phone);
    await verifyPhoneOtp(phone, parsed.data.code);
    return { provider: this.name, externalId: phone };
  }
}
