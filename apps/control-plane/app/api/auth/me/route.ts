import { NextResponse } from "next/server";

import { extractBearerToken, verifyUserJwt } from "@/lib/auth";
import { db } from "@/lib/db";

export const runtime = "nodejs";

export async function GET(request: Request) {
  try {
    const token = extractBearerToken(request);
    const claims = verifyUserJwt(token);

    const user = await db.user.findUnique({
      where: { id: claims.sub },
      select: { id: true, email: true, emailVerified: true, createdAt: true }
    });
    if (!user) {
      return NextResponse.json({ error: "User not found" }, { status: 404 });
    }

    return NextResponse.json(user);
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Auth check failed" },
      { status: 401 }
    );
  }
}
