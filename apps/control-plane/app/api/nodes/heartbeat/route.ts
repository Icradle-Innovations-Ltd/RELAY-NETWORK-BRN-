import { NextResponse } from "next/server";

import { extractBearerToken, verifyNodeJwt } from "@/lib/auth";
import { db } from "@/lib/db";
import { env } from "@/lib/env";
import { takeRateLimitToken } from "@/lib/rate-limit";
import { heartbeatSchema } from "@/lib/validators";

export const runtime = "nodejs";

export async function POST(request: Request) {
  try {
    const ip = request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
    const token = extractBearerToken(request);
    const claims = verifyNodeJwt(token);

    if (!takeRateLimitToken(`heartbeat:${claims.sub}:${ip}`, { capacity: 120, refillPerSecond: 4 })) {
      return NextResponse.json({ error: "Rate limited" }, { status: 429 });
    }

    const parsed = heartbeatSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
    }

    const heartbeat = parsed.data;
    await db.node.update({
      where: { id: claims.sub },
      data: {
        status: heartbeat.status,
        lastSeenAt: new Date(),
        currentPublicIp: heartbeat.currentPublicIp,
        networkType: heartbeat.networkType,
        loadFactor: heartbeat.loadFactor
      }
    });

    const pendingSessions =
      claims.type === "GATEWAY"
        ? await db.session.findMany({
            where: {
              gatewayId: claims.sub,
              status: {
                in: ["PENDING_GATEWAY", "ACTIVE"]
              },
              endedAt: null
            },
            include: {
              client: true,
              gateway: true
            },
            orderBy: { startTime: "desc" },
            take: 20
          })
        : [];

    return NextResponse.json({
      ok: true,
      serverTime: new Date().toISOString(),
      heartbeatIntervalSec: env.NODE_HEARTBEAT_INTERVAL_SEC,
      blacklistVersion: env.BLACKLIST_VERSION,
      assignedSessions: pendingSessions.map((session) => ({
        sessionId: session.id,
        clientId: session.clientId,
        clientWireguardPublicKey: session.client.wireguardPublicKey,
        gatewayWireguardPublicKey: session.gateway.wireguardPublicKey,
        clientTunnelIp: session.clientTunnelIp,
        gatewayTunnelIp: session.gatewayTunnelIp,
        relayToken: session.gatewayRelayToken,
        transportMode: session.transportMode,
        routingMode: session.routingMode,
        requestedCidrs: session.requestedCidrs,
        requestedDomains: session.requestedDomains,
        relay: {
          udpEndpoint: env.RELAY_UDP_ENDPOINT,
          tcpEndpoint: env.RELAY_TCP_ENDPOINT
        }
      }))
    });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Heartbeat failed" },
      { status: 401 }
    );
  }
}
