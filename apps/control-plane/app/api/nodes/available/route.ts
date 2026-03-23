import { NextResponse } from "next/server";

import { db } from "@/lib/db";
import { env } from "@/lib/env";

export const runtime = "nodejs";

export async function GET() {
  const staleAfter = new Date(Date.now() - env.NODE_STALE_AFTER_SEC * 1000);
  const gateways = await db.node.findMany({
    where: {
      type: "GATEWAY",
      status: "ACTIVE",
      lastSeenAt: {
        gte: staleAfter
      }
    },
    orderBy: [{ loadFactor: "asc" }, { lastSeenAt: "desc" }]
  });

  return NextResponse.json({
    gateways: gateways.map((gateway) => ({
      id: gateway.id,
      location: gateway.location,
      networkType: gateway.networkType,
      currentPublicIp: gateway.currentPublicIp,
      loadFactor: gateway.loadFactor,
      capabilities: gateway.capabilities,
      supportedTransports: ["UDP", "TCP_FALLBACK"],
      heartbeatAgeSec: gateway.lastSeenAt
        ? Math.floor((Date.now() - gateway.lastSeenAt.getTime()) / 1000)
        : null
    }))
  });
}
