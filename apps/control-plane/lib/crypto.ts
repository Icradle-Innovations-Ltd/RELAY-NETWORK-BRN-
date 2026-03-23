import { createPublicKey, verify } from "crypto";

import type { RegisterNodeInput } from "./validators";

export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }

  if (Array.isArray(value)) {
    return `[${value.map((item) => stableStringify(item)).join(",")}]`;
  }

  const sortedEntries = Object.entries(value as Record<string, unknown>).sort(([left], [right]) =>
    left.localeCompare(right)
  );
  return `{${sortedEntries
    .map(([key, nested]) => `${JSON.stringify(key)}:${stableStringify(nested)}`)
    .join(",")}}`;
}

export function registrationSigningPayload(input: Omit<RegisterNodeInput, "signature">): Buffer {
  return Buffer.from(
    [
      "BRN_REGISTER_V1",
      input.type,
      input.identityPublicKey,
      input.wireguardPublicKey,
      input.fingerprintHash,
      input.location ?? "",
      stableStringify(input.capabilities ?? {}),
      String(input.timestamp),
      input.nonce
    ].join("|"),
    "utf8"
  );
}

function publicKeySpec(identityPublicKey: string): { key: string | Buffer; format: "pem" | "der"; type: "spki" } {
  const trimmed = identityPublicKey.trim();
  if (trimmed.startsWith("-----BEGIN PUBLIC KEY-----")) {
    return {
      key: trimmed,
      format: "pem",
      type: "spki"
    };
  }

  return {
    key: Buffer.from(trimmed, "base64"),
    format: "der",
    type: "spki"
  };
}

export function verifyRegistrationSignature(input: RegisterNodeInput): boolean {
  const payload = registrationSigningPayload({
    type: input.type,
    identityPublicKey: input.identityPublicKey,
    wireguardPublicKey: input.wireguardPublicKey,
    fingerprintHash: input.fingerprintHash,
    location: input.location,
    capabilities: input.capabilities,
    timestamp: input.timestamp,
    nonce: input.nonce
  });

  const publicKey = createPublicKey(publicKeySpec(input.identityPublicKey));
  const signature = Buffer.from(input.signature, "base64");
  return verify(null, payload, publicKey, signature);
}
