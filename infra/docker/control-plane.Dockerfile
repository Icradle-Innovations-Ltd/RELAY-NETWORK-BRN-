FROM node:22-bookworm-slim

WORKDIR /app
RUN corepack enable

COPY package.json pnpm-workspace.yaml tsconfig.base.json ./
COPY packages ./packages
COPY apps/control-plane ./apps/control-plane

RUN corepack pnpm install --filter @brn/control-plane... --no-frozen-lockfile
RUN corepack pnpm --dir apps/control-plane prisma generate
RUN corepack pnpm --dir apps/control-plane build

WORKDIR /app/apps/control-plane

# Railway injects PORT; Next.js defaults to 3000
ENV PORT=3000
EXPOSE ${PORT}

# Run migrations then start — safe for Railway (single replica)
CMD ["sh", "-c", "corepack pnpm prisma migrate deploy && corepack pnpm start -- -p ${PORT}"]
