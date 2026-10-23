import { LocalDataClient } from "../src/index";

/**
 * Example demonstrating session management, TTL renewal, and rate limiting counters.
 */
async function sessionExample() {
  const client = new LocalDataClient({
    apiKey: process.env.LOCALDATA_API_KEY || "ld_live_test_dummy_key"
  });

  const sessionId = "sess_987654321";
  const sessionData = {
    userId: "usr_42",
    email: "user@example.com",
    loginTime: new Date().toISOString(),
    ip: "192.168.1.100"
  };

  console.log("Creating user session with 30-minute expiration...");
  await client.set(`session:${sessionId}`, sessionData, { ex: 1800 });

  // Read session
  const activeSession = await client.get(`session:${sessionId}`);
  console.log("Active Session:", activeSession);

  // Check TTL
  const remainingTtl = await client.ttl(`session:${sessionId}`);
  console.log(`Remaining TTL: ${remainingTtl} seconds`);

  // Extend TTL (heartbeat)
  await client.expire(`session:${sessionId}`, 3600);
  console.log("Session TTL extended to 1 hour.");

  // Rate-limiting user requests: sliding counter with 60-second window
  const requestCount = await client.incr(`ratelimit:usr_42`, 1);
  if (requestCount === 1) {
    await client.expire(`ratelimit:usr_42`, 60);
  }
  console.log(`User request count in current window: ${requestCount}`);
}

sessionExample().catch(console.error);
