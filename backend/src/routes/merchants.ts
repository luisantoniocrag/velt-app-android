import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { db, type MerchantRow } from "../db/client.js";
import { badRequest, internal } from "../lib/errors.js";

const createMerchantSchema = z.object({
  name: z.string().min(1),
  smartAccountAddress: z.string().regex(/^0x[a-fA-F0-9]{40}$/, "dirección 0x inválida"),
});

export async function merchantRoutes(app: FastifyInstance): Promise<void> {
  // 6.1 POST /api/v1/merchants — registra un comerciante.
  app.post("/merchants", async (request, reply) => {
    const parsed = createMerchantSchema.safeParse(request.body);
    if (!parsed.success) {
      throw badRequest(parsed.error.issues.map((i) => i.message).join("; "), "validation_error");
    }

    const { data, error } = await db
      .from("merchants")
      .insert({ name: parsed.data.name, smart_account_address: parsed.data.smartAccountAddress })
      .select()
      .single<MerchantRow>();

    if (error || !data) {
      request.log.error({ err: error }, "fallo al crear merchant");
      throw internal("no se pudo crear el comerciante");
    }

    return reply.code(201).send({
      id: data.id,
      name: data.name,
      smartAccountAddress: data.smart_account_address,
    });
  });
}
