-- Create enums
CREATE TYPE "NodeType" AS ENUM ('GATEWAY', 'CLIENT');
CREATE TYPE "NodeStatus" AS ENUM ('PENDING', 'ACTIVE', 'DEGRADED', 'OFFLINE', 'BLOCKED');
CREATE TYPE "SessionStatus" AS ENUM ('PENDING_GATEWAY', 'ACTIVE', 'ENDED', 'BLOCKED');
CREATE TYPE "RoutingMode" AS ENUM ('FULL', 'SELECTIVE');
CREATE TYPE "TransportMode" AS ENUM ('UDP', 'TCP_FALLBACK', 'AUTO');

-- Create tables
CREATE TABLE "Node" (
    "id" TEXT NOT NULL,
    "type" "NodeType" NOT NULL,
    "identityPublicKey" TEXT NOT NULL,
    "wireguardPublicKey" TEXT NOT NULL,
    "fingerprintHash" TEXT NOT NULL,
    "ipAddress" TEXT,
    "location" TEXT,
    "status" "NodeStatus" NOT NULL DEFAULT 'PENDING',
    "lastSeenAt" TIMESTAMP(3),
    "heartbeatIntervalSec" INTEGER NOT NULL DEFAULT 30,
    "currentPublicIp" TEXT,
    "networkType" TEXT,
    "loadFactor" INTEGER NOT NULL DEFAULT 0,
    "capabilities" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Node_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Session" (
    "id" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "gatewayId" TEXT NOT NULL,
    "bytesTransferred" BIGINT NOT NULL DEFAULT 0,
    "startTime" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "endedAt" TIMESTAMP(3),
    "status" "SessionStatus" NOT NULL DEFAULT 'PENDING_GATEWAY',
    "routingMode" "RoutingMode" NOT NULL,
    "transportMode" "TransportMode" NOT NULL,
    "requestedCidrs" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "requestedDomains" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "clientTunnelIp" TEXT,
    "gatewayTunnelIp" TEXT,
    "dataCapMb" INTEGER NOT NULL,
    "terminationReason" TEXT,
    "clientRelayToken" TEXT,
    "gatewayRelayToken" TEXT,

    CONSTRAINT "Session_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Usage" (
    "id" TEXT NOT NULL,
    "nodeId" TEXT NOT NULL,
    "sessionId" TEXT NOT NULL,
    "dataUsed" BIGINT NOT NULL,
    "bytesUp" BIGINT NOT NULL,
    "bytesDown" BIGINT NOT NULL,
    "timestamp" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Usage_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "RegistrationNonce" (
    "id" TEXT NOT NULL,
    "identityPublicKey" TEXT NOT NULL,
    "nonce" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RegistrationNonce_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "BlacklistRule" (
    "id" TEXT NOT NULL,
    "value" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "reason" TEXT,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "BlacklistRule_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "PaymentAccount" (
    "id" TEXT NOT NULL,
    "nodeId" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "accountRef" TEXT NOT NULL,
    "credits" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PaymentAccount_pkey" PRIMARY KEY ("id")
);

-- Create indexes
CREATE UNIQUE INDEX "Node_identityPublicKey_key" ON "Node"("identityPublicKey");
CREATE INDEX "Session_gatewayId_status_idx" ON "Session"("gatewayId", "status");
CREATE INDEX "Session_clientId_status_idx" ON "Session"("clientId", "status");
CREATE INDEX "Usage_nodeId_timestamp_idx" ON "Usage"("nodeId", "timestamp");
CREATE INDEX "Usage_sessionId_timestamp_idx" ON "Usage"("sessionId", "timestamp");
CREATE UNIQUE INDEX "RegistrationNonce_identityPublicKey_nonce_key" ON "RegistrationNonce"("identityPublicKey", "nonce");
CREATE UNIQUE INDEX "BlacklistRule_value_key" ON "BlacklistRule"("value");
CREATE INDEX "PaymentAccount_nodeId_provider_idx" ON "PaymentAccount"("nodeId", "provider");

-- Foreign keys
ALTER TABLE "Session" ADD CONSTRAINT "Session_clientId_fkey"
    FOREIGN KEY ("clientId") REFERENCES "Node"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "Session" ADD CONSTRAINT "Session_gatewayId_fkey"
    FOREIGN KEY ("gatewayId") REFERENCES "Node"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "Usage" ADD CONSTRAINT "Usage_nodeId_fkey"
    FOREIGN KEY ("nodeId") REFERENCES "Node"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "Usage" ADD CONSTRAINT "Usage_sessionId_fkey"
    FOREIGN KEY ("sessionId") REFERENCES "Session"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "PaymentAccount" ADD CONSTRAINT "PaymentAccount_nodeId_fkey"
    FOREIGN KEY ("nodeId") REFERENCES "Node"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
