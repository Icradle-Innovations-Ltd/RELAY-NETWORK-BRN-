import { NextResponse } from "next/server";

import { createNodeJwt, extractBearerToken, verifyUserJwt } from "@/lib/auth";
import { db } from "@/lib/db";
import { takeRateLimitToken } from "@/lib/rate-limit";

import { z } from "zod";

const registerClientSchema = z.object({
  identityPublicKey: z.string().min(16),
  wireguardPublicKey: z.string().min(16),
  fingerprintHash: z.string().regex(/^[0-9a-f]{64}$/i)
});

export const runtime = "nodejs";

export async function POST(request: Request) {
  try {
    const token = extractBearerToken(request);
    const claims = verifyUserJwt(token);

    if (!takeRateLimitToken(`user-register-client:${claims.sub}`, { capacity: 5, refillPerSecond: 0.05 })) {
      return NextResponse.json({ error: "Rate limited" }, { status: 429 });
    }

    const parsed = registerClientSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
    }

    const { identityPublicKey, wireguardPublicKey, fingerprintHash } = parsed.data;

    const node = await db.node.upsert({
      where: { identityPublicKey },
      update: { wireguardPublicKey, fingerprintHash, status: "ACTIVE", lastSeenAt: new Date() },
      create: {
        type: "CLIENT",
        identityPublicKey,
        wireguardPublicKey,
        fingerprintHash,
        status: "ACTIVE",
        lastSeenAt: new Date(),
        userId: claims.sub
      }
    });

    const nodeToken = createNodeJwt(node.id, "CLIENT");

    return NextResponse.json({
      nodeId: node.id,
      token: nodeToken,
      heartbeatIntervalSec: 30
    }, { status: 201 });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Client registration failed" },
      { status: 401 }
    );
  }
}
