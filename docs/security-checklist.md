# Security Checklist

- [x] Signed node registration with Ed25519 verification
- [x] Timestamp and nonce replay checks on registration
- [x] Short-lived node JWTs for API access
- [x] Short-lived relay session tokens validated offline by the relay
- [x] Per-IP and per-node in-memory rate limiting
- [x] Session byte counters and quota fields
- [x] Gateway health gating in node discovery
- [x] Signed relay usage ingestion
- [x] Blacklist table and versioning hooks in the control plane
- [ ] Relay TLS certificate provisioning for TCP fallback on port 443
- [ ] Android Keystore hardening and hardware-backed attestation where supported
- [ ] Production WAF / DDoS controls at the VPS edge
- [ ] Formal audit of userspace WireGuard integration on Android
