import { createHmac, randomBytes } from "node:crypto";
import { config } from "../config.js";

// Cliente del bioserver de OpenPalm, portado de VeltSensorBioService.verifyUser (app Android).
// Firma cada request con HMAC-SHA256 sobre un JSON canónico y devuelve el personId identificado.

const PATH = "api/subject/identify";

interface IdentifyTemplate {
  biolocation: string;
  templates: { type: string; template: string }[];
}

function buildAuthorization(body: unknown, nonce: string, timestamp: number): string {
  // El orden de las claves importa: el bioserver recomputa la firma sobre este mismo JSON.
  const canonical = JSON.stringify({
    body,
    clientId: config.BIOSERVER_CLIENT_ID,
    method: "post",
    nonce,
    path: PATH,
    query: {},
    timestamp,
  });

  const signature = createHmac("sha256", config.BIOSERVER_SHARED_SECRET)
    .update(canonical, "utf8")
    .digest("hex");

  return `MAC ${signature}, clientId=${config.BIOSERVER_CLIENT_ID}, nonce=${nonce}, timestamp=${timestamp}`;
}

// Busca el personId en una respuesta de forma estable sin conocer la forma exacta: recorre el
// JSON y devuelve el primer valor de una clave tipo personId/subjectId/id. Defensivo a propósito.
function extractPersonId(payload: unknown): string | null {
  const keys = ["personId", "person_id", "subjectId", "subject_id", "subjectID", "id"];
  const seen = new Set<unknown>();

  const walk = (node: unknown): string | null => {
    if (node === null || typeof node !== "object") return null;
    if (seen.has(node)) return null;
    seen.add(node);

    if (Array.isArray(node)) {
      for (const item of node) {
        const found = walk(item);
        if (found) return found;
      }
      return null;
    }

    const obj = node as Record<string, unknown>;
    for (const key of keys) {
      const value = obj[key];
      if (typeof value === "string" && value.length > 0) return value;
      if (typeof value === "number") return String(value);
    }
    for (const value of Object.values(obj)) {
      const found = walk(value);
      if (found) return found;
    }
    return null;
  };

  return walk(payload);
}

export interface BioserverLogger {
  info(obj: unknown, msg?: string): void;
  warn(obj: unknown, msg?: string): void;
}

// Identifica una palma. Devuelve el personId o lanza si el bioserver falla o no reconoce la palma.
export async function identifyPalm(templateBase64: string, log: BioserverLogger): Promise<string> {
  const body: IdentifyTemplate[] = [
    {
      biolocation: "UnknownPalmVeinCapture",
      templates: [{ type: "FujitsuRFormat", template: templateBase64 }],
    },
  ];

  const nonce = randomBytes(36).toString("hex");
  const timestamp = Math.floor(Date.now() / 1000);
  const authorization = buildAuthorization(body, nonce, timestamp);

  const res = await fetch(`${config.BIOSERVER_URL}${PATH}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: authorization },
    body: JSON.stringify(body),
  });

  const raw = await res.text();
  if (!res.ok) {
    log.warn({ status: res.status, body: raw }, "bioserver identify no-2xx");
    throw new Error(`bioserver_identify_failed: HTTP ${res.status}`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    log.warn({ body: raw }, "bioserver identify devolvió un body no-JSON");
    throw new Error("bioserver_identify_unparsable");
  }

  const personId = extractPersonId(parsed);
  if (!personId) {
    // No se encontró el campo: logueamos el body crudo para fijar la ruta del personId.
    log.warn({ body: parsed }, "no se encontró personId en la respuesta del bioserver");
    throw new Error("palm_not_recognized");
  }

  log.info({ personId }, "palma identificada por el bioserver");
  return personId;
}
