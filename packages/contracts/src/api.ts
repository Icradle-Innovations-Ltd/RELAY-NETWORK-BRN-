export type NodeType = "GATEWAY" | "CLIENT";
export type NodeStatus = "PENDING" | "ACTIVE" | "DEGRADED" | "OFFLINE" | "BLOCKED";
export type RoutingMode = "FULL" | "SELECTIVE";
export type TransportMode = "UDP" | "TCP_FALLBACK" | "AUTO";

export interface RegisterNodeRequest {
  type: NodeType;
  identityPublicKey: string;
  wireguardPublicKey: string;
  fingerprintHash: string;
  location?: string;
  capabilities?: Record<string, unknown>;
  timestamp: number;
  nonce: string;
  signature: string;
}

export interface RegisterNodeResponse {
  nodeId: string;
  token: string;
  heartbeatIntervalSec: number;
  relay: {
    udpEndpoint: string;
    tcpEndpoint: string;
  };
}

export interface SessionStartRequest {
  gatewayId: string;
  routingMode: RoutingMode;
  transportPreference: TransportMode;
  requestedCidrs?: string[];
  requestedDomains?: string[];
  dataCapMb?: number;
}

export interface AvailableGateway {
  id: string;
  location: string | null;
  networkType: string | null;
  currentPublicIp: string | null;
  loadFactor: number;
  capabilities: Record<string, unknown> | null;
  supportedTransports: string[];
  heartbeatAgeSec: number | null;
}
