import { NextResponse } from "next/server";

import { createNodeJwt } from "@/lib/auth";
import { verifyRegistrationSignature } from "@/lib/crypto";
import { db } from "@/lib/db";
import { env } from "@/lib/env";
import { takeRateLimitToken } from "@/lib/rate-limit";
import { registerNodeSchema } from "@/lib/validators";

export const runtime = "nodejs";

function inferClientIp(request: Request): string {
  return request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
}

export async function POST(request: Request) {
  try {
    const ip = inferClientIp(request);
    if (!takeRateLimitToken(`register:ip:${ip}`, { capacity: 12, refillPerSecond: 0.5 })) {
      return NextResponse.json({ error: "Rate limited" }, { status: 429 });
    }

    const parsed = registerNodeSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
    }

    const body = parsed.data;
    const now = Date.now();
    if (Math.abs(now - body.timestamp) > 5 * 60 * 1000) {
      return NextResponse.json({ error: "Registration timestamp outside allowed window" }, { status: 400 });
    }

    const nonceAlreadyUsed = await db.registrationNonce.findUnique({
      where: {
        identityPublicKey_nonce: {
          identityPublicKey: body.identityPublicKey,
          nonce: body.nonce
        }
      }
    });
    if (nonceAlreadyUsed) {
      return NextResponse.json({ error: "Registration nonce replayed" }, { status: 409 });
    }

    if (!verifyRegistrationSignature(body)) {
      return NextResponse.json({ error: "Invalid registration signature" }, { status: 401 });
    }

    const node = await db.node.upsert({
      where: { identityPublicKey: body.identityPublicKey },
      create: {
        type: body.type,
        identityPublicKey: body.identityPublicKey,
        wireguardPublicKey: body.wireguardPublicKey,
        fingerprintHash: body.fingerprintHash,
        location: body.location,
        capabilities: body.capabilities ?? {},
        status: "ACTIVE",
        ipAddress: ip,
        currentPublicIp: ip,
        lastSeenAt: new Date(),
        heartbeatIntervalSec: env.NODE_HEARTBEAT_INTERVAL_SEC
      },
      update: {
        type: body.type,
        wireguardPublicKey: body.wireguardPublicKey,
        fingerprintHash: body.fingerprintHash,
        location: body.location,
        capabilities: body.capabilities ?? {},
        status: "ACTIVE",
        ipAddress: ip,
        currentPublicIp: ip,
        lastSeenAt: new Date(),
        heartbeatIntervalSec: env.NODE_HEARTBEAT_INTERVAL_SEC
      }
    });

    await db.registrationNonce.create({
      data: {
        identityPublicKey: body.identityPublicKey,
        nonce: body.nonce
      }
    });

    return NextResponse.json({
      nodeId: node.id,
      token: createNodeJwt(node.id, node.type),
      heartbeatIntervalSec: env.NODE_HEARTBEAT_INTERVAL_SEC,
      relay: {
        udpEndpoint: env.RELAY_UDP_ENDPOINT,
        tcpEndpoint: env.RELAY_TCP_ENDPOINT
      }
    });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Registration failed" },
      { status: 500 }
    );
  }
}
