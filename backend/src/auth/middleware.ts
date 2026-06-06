import type { FastifyReply, FastifyRequest } from "fastify";
import { unauthorized } from "../lib/errors.js";
import { verifyAccessToken } from "./tokens.js";

declare module "fastify" {
  interface FastifyRequest {
    merchantId?: string;
  }
}

// preHandler: exige un access token válido y deja el merchant autenticado en request.merchantId.
export async function requireMerchantAuth(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    throw unauthorized("falta el token Bearer", "missing_token");
  }

  try {
    const { merchantId } = verifyAccessToken(header.slice("Bearer ".length).trim());
    request.merchantId = merchantId;
  } catch {
    throw unauthorized("token inválido o expirado", "invalid_token");
  }
}
