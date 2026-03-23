import { NextResponse } from "next/server";

import { extractBearerToken, verifyNodeJwt } from "@/lib/auth";
import { db } from "@/lib/db";
import { env } from "@/lib/env";
import { allocateTunnel } from "@/lib/ipam";
import { takeRateLimitToken } from "@/lib/rate-limit";
import { createRelaySessionToken } from "@/lib/session-token";
import { sessionStartSchema } from "@/lib/validators";

export const runtime = "nodejs";

export async function POST(request: Request) {
  try {
    const token = extractBearerToken(request);
    const claims = verifyNodeJwt(token);
    if (claims.type !== "CLIENT") {
      return NextResponse.json({ error: "Only client nodes can start sessions" }, { status: 403 });
    }

    if (!takeRateLimitToken(`session:${claims.sub}`, { capacity: 20, refillPerSecond: 0.25 })) {
      return NextResponse.json({ error: "Rate limited" }, { status: 429 });
    }

    const parsed = sessionStartSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
    }

    const input = parsed.data;
    const client = await db.node.findUnique({ where: { id: claims.sub } });
    const gateway = await db.node.findUnique({ where: { id: input.gatewayId } });
    if (!client || !gateway || gateway.type !== "GATEWAY") {
      return NextResponse.json({ error: "Gateway not found" }, { status: 404 });
    }
    if (
      gateway.status !== "ACTIVE" ||
      !gateway.lastSeenAt ||
      gateway.lastSeenAt < new Date(Date.now() - env.NODE_STALE_AFTER_SEC * 1000)
    ) {
      return NextResponse.json({ error: "Gateway is not healthy" }, { status: 409 });
    }

    const capMb = Math.min(input.dataCapMb ?? env.DEFAULT_SESSION_CAP_MB, env.MAX_SESSION_CAP_MB);
    const session = await db.session.create({
      data: {
        clientId: client.id,
        gatewayId: gateway.id,
        status: "PENDING_GATEWAY",
        routingMode: input.routingMode,
        transportMode: input.transportPreference,
        dataCapMb: capMb,
        requestedCidrs: input.requestedCidrs ?? [],
        requestedDomains: input.requestedDomains ?? []
      }
    });

    const tunnel = allocateTunnel(session.id);
    const clientRelayToken = createRelaySessionToken({
      session,
      clientWireguardPublicKey: client.wireguardPublicKey,
      gatewayWireguardPublicKey: gateway.wireguardPublicKey,
      clientTunnelIp: tunnel.clientTunnelIp,
      gatewayTunnelIp: tunnel.gatewayTunnelIp,
      role: "client",
      routingMode: input.routingMode,
      transportMode: input.transportPreference,
      quotaMb: capMb
    });
    const gatewayRelayToken = createRelaySessionToken({
      session,
      clientWireguardPublicKey: client.wireguardPublicKey,
      gatewayWireguardPublicKey: gateway.wireguardPublicKey,
      clientTunnelIp: tunnel.clientTunnelIp,
      gatewayTunnelIp: tunnel.gatewayTunnelIp,
      role: "gateway",
      routingMode: input.routingMode,
      transportMode: input.transportPreference,
      quotaMb: capMb
    });

    await db.session.update({
      where: { id: session.id },
      data: {
        clientTunnelIp: tunnel.clientTunnelIp,
        gatewayTunnelIp: tunnel.gatewayTunnelIp,
        clientRelayToken,
        gatewayRelayToken,
        status: "ACTIVE"
      }
    });

    return NextResponse.json({
      sessionId: session.id,
      relayToken: clientRelayToken,
      relay: {
        udpEndpoint: env.RELAY_UDP_ENDPOINT,
        tcpEndpoint: env.RELAY_TCP_ENDPOINT
      },
      tunnel: {
        clientTunnelIp: tunnel.clientTunnelIp,
        gatewayTunnelIp: tunnel.gatewayTunnelIp,
        networkCidr: tunnel.networkCidr,
        dnsServers: tunnel.dnsServers,
        keepaliveSec: 25,
        mtu: 1280
      },
      peer: {
        wireguardPublicKey: gateway.wireguardPublicKey
      },
      routingMode: input.routingMode,
      transportMode: input.transportPreference,
      requestedCidrs: input.requestedCidrs ?? [],
      requestedDomains: input.requestedDomains ?? [],
      dataCapMb: capMb,
      blacklistVersion: env.BLACKLIST_VERSION
    });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Session start failed" },
      { status: 401 }
    );
  }
}
