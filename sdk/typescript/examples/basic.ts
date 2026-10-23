import { LocalDataClient } from "../src/index";

async function main() {
  console.log("🚀 Running LocalData SDK Basic Example...\n");

  const client = new LocalDataClient({
    apiKey: process.env.LOCALDATA_API_KEY || "ld_live_test_dummy_key",
    baseUrl: process.env.LOCALDATA_URL || "http://localhost:8080"
  });

  // 1. Set string and object
  console.log("1. Setting key-values...");
  await client.set("user:101", { name: "Alice", role: "admin", visits: 1 });
  await client.set("config:theme", "dark", { ex: 3600 });

  // 2. Get values
  console.log("2. Retrieving key-values...");
  const user = await client.get("user:101");
  console.log("   Retrieved user:", user);

  const theme = await client.get("config:theme");
  console.log("   Retrieved theme:", theme);

  // 3. Atomic increments
  console.log("3. Atomic counters...");
  const visits = await client.incr("counter:pageviews", 5);
  console.log("   Pageviews counter:", visits);

  // 4. Key scan
  console.log("4. Scanning keys...");
  const scan = await client.scan({ pattern: "*" });
  console.log("   Found keys:", scan.keys);

  // 5. Cleanup
  console.log("5. Cleaning up...");
  await client.del("user:101");
  await client.del("config:theme");
  await client.del("counter:pageviews");

  console.log("\n✅ Basic example completed successfully!");
}

main().catch(console.error);
