import { z } from "zod";

const nodeTypeSchema = z.enum(["GATEWAY", "CLIENT"]);
const routingModeSchema = z.enum(["FULL", "SELECTIVE"]);
const transportModeSchema = z.enum(["UDP", "TCP_FALLBACK", "AUTO"]);

export const registerNodeSchema = z.object({
  type: nodeTypeSchema,
  identityPublicKey: z.string().min(16),
  wireguardPublicKey: z.string().min(16),
  fingerprintHash: z.string().regex(/^[0-9a-f]{64}$/i),
  location: z.string().max(120).optional(),
  capabilities: z.record(z.unknown()).optional(),
  timestamp: z.number().int().positive(),
  nonce: z.string().min(8).max(128),
  signature: z.string().min(32)
});

export const heartbeatSchema = z.object({
  status: z.enum(["ACTIVE", "DEGRADED", "OFFLINE"]),
  activeSessions: z.number().int().min(0).max(10_000),
  relayHealthy: z.boolean(),
  batteryPercent: z.number().int().min(0).max(100).nullable().optional(),
  networkType: z.string().max(32).nullable().optional(),
  currentPublicIp: z.string().max(64).nullable().optional(),
  loadFactor: z.number().int().min(0).max(100).default(0),
  appVersion: z.string().max(64).nullable().optional()
});

export const sessionStartSchema = z.object({
  gatewayId: z.string().min(10),
  routingMode: routingModeSchema,
  transportPreference: transportModeSchema.default("AUTO"),
  requestedCidrs: z.array(z.string().min(3)).max(64).optional(),
  requestedDomains: z.array(z.string().min(3)).max(64).optional(),
  dataCapMb: z.number().int().positive().max(10_240).optional()
});

export const billingUsageSchema = z.object({
  sessionId: z.string().min(10),
  gatewayId: z.string().min(10),
  clientId: z.string().min(10),
  bytesUp: z.number().int().min(0),
  bytesDown: z.number().int().min(0),
  transportMode: transportModeSchema,
  durationSec: z.number().int().min(0),
  terminationReason: z.string().max(120)
});

export type RegisterNodeInput = z.infer<typeof registerNodeSchema>;
export type HeartbeatInput = z.infer<typeof heartbeatSchema>;
export type SessionStartInput = z.infer<typeof sessionStartSchema>;
export type BillingUsageInput = z.infer<typeof billingUsageSchema>;
