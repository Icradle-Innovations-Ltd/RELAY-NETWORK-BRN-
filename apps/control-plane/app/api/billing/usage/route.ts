import { createHmac, timingSafeEqual } from "crypto";

import { NextResponse } from "next/server";

import { db } from "@/lib/db";
import { env } from "@/lib/env";
import { billingUsageSchema } from "@/lib/validators";

export const runtime = "nodejs";

function verifyRelaySignature(body: string, providedSignature: string | null): boolean {
  if (!providedSignature) {
    return false;
  }

  const expected = createHmac("sha256", env.BRN_RELAY_USAGE_SECRET).update(body).digest("hex");
  const expectedBuffer = Buffer.from(expected, "hex");
  const providedBuffer = Buffer.from(providedSignature, "hex");
  return expectedBuffer.length === providedBuffer.length && timingSafeEqual(expectedBuffer, providedBuffer);
}

export async function POST(request: Request) {
  const rawBody = await request.text();
  if (!verifyRelaySignature(rawBody, request.headers.get("x-brn-relay-signature"))) {
    return NextResponse.json({ error: "Invalid relay signature" }, { status: 401 });
  }

  const parsed = billingUsageSchema.safeParse(JSON.parse(rawBody));
  if (!parsed.success) {
    return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
  }

  const usage = parsed.data;
  const totalBytes = BigInt(usage.bytesUp + usage.bytesDown);
  const session = await db.session.update({
    where: { id: usage.sessionId },
    data: {
      bytesTransferred: {
        increment: totalBytes
      },
      terminationReason: usage.terminationReason,
      endedAt: new Date(),
      status: "ENDED"
    }
  });

  await db.usage.create({
    data: {
      nodeId: usage.gatewayId,
      sessionId: session.id,
      dataUsed: totalBytes,
      bytesUp: BigInt(usage.bytesUp),
      bytesDown: BigInt(usage.bytesDown)
    }
  });

  return NextResponse.json({ ok: true });
}
