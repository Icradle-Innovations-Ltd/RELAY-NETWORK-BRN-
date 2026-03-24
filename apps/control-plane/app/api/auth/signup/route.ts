import { NextResponse } from "next/server";

import { createUserJwt } from "@/lib/auth";
import { db } from "@/lib/db";
import { hashPassword } from "@/lib/password";
import { takeRateLimitToken } from "@/lib/rate-limit";
import { signupSchema } from "@/lib/validators";

export const runtime = "nodejs";

export async function POST(request: Request) {
  try {
    if (!takeRateLimitToken(`signup:${request.headers.get("x-forwarded-for") ?? "unknown"}`, { capacity: 5, refillPerSecond: 0.02 })) {
      return NextResponse.json({ error: "Rate limited" }, { status: 429 });
    }

    const parsed = signupSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });
    }

    const { email, password } = parsed.data;
    const normalizedEmail = email.toLowerCase().trim();

    const existing = await db.user.findUnique({ where: { email: normalizedEmail } });
    if (existing) {
      return NextResponse.json({ error: "Email already registered" }, { status: 409 });
    }

    const passwordHash = hashPassword(password);
    const user = await db.user.create({
      data: { email: normalizedEmail, passwordHash }
    });

    const token = createUserJwt(user.id, user.email);
    return NextResponse.json({ userId: user.id, token }, { status: 201 });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Signup failed" },
      { status: 500 }
    );
  }
}
