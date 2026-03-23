import { z } from "zod";

const envSchema = z.object({
  DATABASE_URL: z.string().min(1),
  BRN_JWT_SECRET: z.string().min(32),
  BRN_SESSION_SECRET: z.string().min(32),
  BRN_RELAY_USAGE_SECRET: z.string().min(32),
  RELAY_UDP_ENDPOINT: z.string().min(1),
  RELAY_TCP_ENDPOINT: z.string().min(1),
  NODE_HEARTBEAT_INTERVAL_SEC: z.coerce.number().int().positive().default(30),
  NODE_STALE_AFTER_SEC: z.coerce.number().int().positive().default(90),
  DEFAULT_SESSION_CAP_MB: z.coerce.number().int().positive().default(1024),
  MAX_SESSION_CAP_MB: z.coerce.number().int().positive().default(2048),
  BLACKLIST_VERSION: z.string().default("dev")
});

export const env = envSchema.parse(process.env);
