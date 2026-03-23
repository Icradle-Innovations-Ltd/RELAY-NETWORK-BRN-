import type { RoutingMode, Session, TransportMode } from "@prisma/client";

import { env } from "./env";
import { signJwt } from "./jwt";

export interface RelaySessionTokenClaims {
  sid: string;
  cid: string;
  gid: string;
  role: "client" | "gateway";
  clientTunnelIp: string;
  gatewayTunnelIp: string;
  clientWireguardPublicKey: string;
  gatewayWireguardPublicKey: string;
  quotaMb: number;
  routingMode: RoutingMode;
  transportMode: TransportMode;
  exp: number;
  iat: number;
}

export function createRelaySessionToken(input: {
  session: Session;
  clientWireguardPublicKey: string;
  gatewayWireguardPublicKey: string;
  clientTunnelIp: string;
  gatewayTunnelIp: string;
  role: "client" | "gateway";
  routingMode: RoutingMode;
  transportMode: TransportMode;
  quotaMb: number;
}): string {
  const iat = Math.floor(Date.now() / 1000);
  return signJwt<RelaySessionTokenClaims>(
    {
      sid: input.session.id,
      cid: input.session.clientId,
      gid: input.session.gatewayId,
      role: input.role,
      clientTunnelIp: input.clientTunnelIp,
      gatewayTunnelIp: input.gatewayTunnelIp,
      clientWireguardPublicKey: input.clientWireguardPublicKey,
      gatewayWireguardPublicKey: input.gatewayWireguardPublicKey,
      quotaMb: input.quotaMb,
      routingMode: input.routingMode,
      transportMode: input.transportMode,
      iat,
      exp: iat + 60 * 20
    },
    env.BRN_SESSION_SECRET
  );
}
