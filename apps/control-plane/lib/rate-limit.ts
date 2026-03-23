type Bucket = {
  tokens: number;
  lastRefillMs: number;
};

const buckets = new Map<string, Bucket>();

export interface RateLimitConfig {
  capacity: number;
  refillPerSecond: number;
}

export function takeRateLimitToken(key: string, config: RateLimitConfig): boolean {
  const now = Date.now();
  const bucket = buckets.get(key) ?? {
    tokens: config.capacity,
    lastRefillMs: now
  };

  const elapsedSeconds = (now - bucket.lastRefillMs) / 1000;
  bucket.tokens = Math.min(config.capacity, bucket.tokens + elapsedSeconds * config.refillPerSecond);
  bucket.lastRefillMs = now;

  if (bucket.tokens < 1) {
    buckets.set(key, bucket);
    return false;
  }

  bucket.tokens -= 1;
  buckets.set(key, bucket);
  return true;
}
