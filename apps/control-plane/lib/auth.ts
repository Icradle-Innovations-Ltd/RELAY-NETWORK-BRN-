import { type NodeType } from "@prisma/client";

import { env } from "./env";
import { signJwt, verifyJwt } from "./jwt";

export interface NodeJwtClaims {
  sub: string;
  type: NodeType;
  exp: number;
  iat: number;
}

export function createNodeJwt(nodeId: string, type: NodeType): string {
  const iat = Math.floor(Date.now() / 1000);
  return signJwt<NodeJwtClaims>(
    {
      sub: nodeId,
      type,
      iat,
      exp: iat + 60 * 60 * 12
    },
    env.BRN_JWT_SECRET
  );
}

export function verifyNodeJwt(token: string): NodeJwtClaims {
  return verifyJwt<NodeJwtClaims>(token, env.BRN_JWT_SECRET);
}

export function extractBearerToken(request: Request): string {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    throw new Error("Missing bearer token");
  }
  return header.slice("Bearer ".length);
}
