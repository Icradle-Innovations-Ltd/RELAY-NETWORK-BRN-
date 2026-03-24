FROM node:22-bookworm-slim

RUN apt-get update -y && apt-get install -y openssl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN corepack enable

COPY package.json pnpm-workspace.yaml tsconfig.base.json ./
COPY packages ./packages
COPY apps/control-plane ./apps/control-plane

RUN corepack pnpm install --filter @brn/control-plane... --no-frozen-lockfile
RUN corepack pnpm --dir apps/control-plane prisma generate

# Next.js collects page data at build time, which triggers Zod env validation.
# Pass dummy values as shell vars for the build command only — NOT as persistent ENV.
RUN DATABASE_URL="postgresql://x:x@localhost:5432/x" \
    BRN_JWT_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!" \
    BRN_SESSION_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!" \
    BRN_RELAY_USAGE_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!" \
    RELAY_UDP_ENDPOINT="0.0.0.0:51820" \
    RELAY_TCP_ENDPOINT="0.0.0.0:8443" \
    corepack pnpm --dir apps/control-plane build

WORKDIR /app/apps/control-plane

# Railway injects PORT; Next.js defaults to 3000
ENV PORT=3000
EXPOSE ${PORT}

CMD ["sh", "-c", "npx next start -p ${PORT}"]
