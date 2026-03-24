import { randomBytes, scryptSync, timingSafeEqual } from "crypto";

const SALT_LENGTH = 32;
const KEY_LENGTH = 64;
const SCRYPT_COST = 16384;

export function hashPassword(password: string): string {
  const salt = randomBytes(SALT_LENGTH);
  const derived = scryptSync(password, salt, KEY_LENGTH, { N: SCRYPT_COST, r: 8, p: 1 });
  return `${salt.toString("hex")}:${derived.toString("hex")}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  const [saltHex, keyHex] = stored.split(":");
  if (!saltHex || !keyHex) return false;
  const salt = Buffer.from(saltHex, "hex");
  const storedKey = Buffer.from(keyHex, "hex");
  const derived = scryptSync(password, salt, KEY_LENGTH, { N: SCRYPT_COST, r: 8, p: 1 });
  if (derived.length !== storedKey.length) return false;
  return timingSafeEqual(derived, storedKey);
}
