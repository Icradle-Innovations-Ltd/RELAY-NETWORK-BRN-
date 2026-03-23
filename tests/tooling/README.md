# BRN Test Tooling

## Gateway simulator

Registers a synthetic gateway node, sends heartbeats, and announces relay presence for assigned sessions.

```powershell
go run ./tests/tooling/cmd/gateway-sim --api http://localhost:3000/api --state .gateway-sim.json
```

## Load test

Reads JSONL session scenarios and opens the requested number of UDP relay flows.

```powershell
go run ./tests/tooling/cmd/loadtest --scenario-file .\tests\tooling\load-scenarios.example.jsonl
```

Each JSONL line should include:

- `nodeId`
- `relayToken`
- `relayUdp`
- `role`
- `packets`
- `packetBytes`

