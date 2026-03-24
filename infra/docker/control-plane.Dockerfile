FROM node:22-bookworm-slim

WORKDIR /app
RUN corepack enable

COPY package.json pnpm-workspace.yaml tsconfig.base.json ./
COPY packages ./packages
COPY apps/control-plane ./apps/control-plane

RUN corepack pnpm install --filter @brn/control-plane... --no-frozen-lockfile
RUN corepack pnpm --dir apps/control-plane prisma generate

# Dummy env vars so Next.js can collect page data at build time.
# Real values are injected at runtime via docker-compose .env.
ARG DATABASE_URL="postgresql://x:x@localhost:5432/x"
ARG BRN_JWT_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!"
ARG BRN_SESSION_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!"
ARG BRN_RELAY_USAGE_SECRET="build-placeholder-secret-that-is-at-least-32-chars-long!!"
ARG RELAY_UDP_ENDPOINT="0.0.0.0:51820"
ARG RELAY_TCP_ENDPOINT="0.0.0.0:8443"
ENV DATABASE_URL=$DATABASE_URL \
    BRN_JWT_SECRET=$BRN_JWT_SECRET \
    BRN_SESSION_SECRET=$BRN_SESSION_SECRET \
    BRN_RELAY_USAGE_SECRET=$BRN_RELAY_USAGE_SECRET \
    RELAY_UDP_ENDPOINT=$RELAY_UDP_ENDPOINT \
    RELAY_TCP_ENDPOINT=$RELAY_TCP_ENDPOINT
RUN corepack pnpm --dir apps/control-plane build
# Clear build-time placeholders so runtime must supply real values
ENV DATABASE_URL="" \
    BRN_JWT_SECRET="" \
    BRN_SESSION_SECRET="" \
    BRN_RELAY_USAGE_SECRET="" \
    RELAY_UDP_ENDPOINT="" \
    RELAY_TCP_ENDPOINT=""

WORKDIR /app/apps/control-plane

# Railway injects PORT; Next.js defaults to 3000
ENV PORT=3000
EXPOSE ${PORT}

CMD ["sh", "-c", "corepack pnpm start -- -p ${PORT}"]
