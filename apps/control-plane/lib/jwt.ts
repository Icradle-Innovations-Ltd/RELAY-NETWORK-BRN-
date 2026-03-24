import { createHmac, timingSafeEqual } from "crypto";

type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

function encodeBase64Url(input: Buffer | string): string {
  return Buffer.from(input)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function decodeBase64Url(input: string): Buffer {
  const normalized = input.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  return Buffer.from(padded, "base64");
}

export function signJwt<T>(payload: T, secret: string): string {
  const encodedHeader = encodeBase64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const encodedPayload = encodeBase64Url(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = createHmac("sha256", secret).update(signingInput).digest();
  return `${signingInput}.${encodeBase64Url(signature)}`;
}

export function verifyJwt<T>(token: string, secret: string): T {
  const segments = token.split(".");
  if (segments.length !== 3) {
    throw new Error("Malformed token");
  }

  const [encodedHeader, encodedPayload, encodedSignature] = segments;
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const expected = createHmac("sha256", secret).update(signingInput).digest();
  const actual = decodeBase64Url(encodedSignature);
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
    throw new Error("Invalid token signature");
  }

  const raw = JSON.parse(decodeBase64Url(encodedPayload).toString("utf8")) as Record<string, unknown>;
  if (typeof raw.exp === "number" && raw.exp < Math.floor(Date.now() / 1000)) {
    throw new Error("Token expired");
  }

  return raw as T;
}
