export default function HomePage() {
  return (
    <main style={{ fontFamily: "ui-sans-serif, system-ui", padding: "3rem", lineHeight: 1.5 }}>
      <h1>BRN Control Plane</h1>
      <p>
        This app exposes node registration, heartbeats, session management, and usage ingestion APIs
        for the Bandwidth Relay Network.
      </p>
      <ul>
        <li>`POST /api/nodes/register`</li>
        <li>`POST /api/nodes/heartbeat`</li>
        <li>`GET /api/nodes/available`</li>
        <li>`POST /api/sessions/start`</li>
        <li>`POST /api/billing/usage`</li>
      </ul>
    </main>
  );
}
