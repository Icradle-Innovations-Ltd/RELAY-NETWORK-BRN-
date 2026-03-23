import { createHash } from "crypto";

export interface TunnelAllocation {
  clientTunnelIp: string;
  gatewayTunnelIp: string;
  networkCidr: string;
  dnsServers: string[];
}

export function allocateTunnel(sessionId: string): TunnelAllocation {
  const digest = createHash("sha256").update(sessionId).digest();
  const octet2 = 64 + (digest[0] % 32);
  const octet3 = digest[1];
  const base = (digest[2] % 252) + 1;
  const clientHost = base;
  const gatewayHost = base === 254 ? 253 : base + 1;

  return {
    clientTunnelIp: `100.${octet2}.${octet3}.${clientHost}/32`,
    gatewayTunnelIp: `100.${octet2}.${octet3}.${gatewayHost}/32`,
    networkCidr: `100.${octet2}.${octet3}.0/24`,
    dnsServers: ["1.1.1.1", "8.8.8.8"]
  };
}
