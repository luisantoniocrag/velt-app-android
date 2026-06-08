import type { FastifyReply, FastifyRequest } from "fastify";
import { unauthorized } from "../lib/errors.js";
import { verifyAccessToken } from "./tokens.js";

declare module "fastify" {
  interface FastifyRequest {
    userId?: string;
  }
}

// preHandler: exige un access token válido y deja el usuario autenticado en request.userId.
export async function requireAuth(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    throw unauthorized("falta el token Bearer", "missing_token");
  }

  try {
    const { userId } = verifyAccessToken(header.slice("Bearer ".length).trim());
    request.userId = userId;
  } catch {
    throw unauthorized("token inválido o expirado", "invalid_token");
  }
}
