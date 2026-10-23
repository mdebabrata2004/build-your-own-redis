const { LocalDataClient } = require("./dist/index.js");

async function run() {
  console.log("=== Testing TypeScript / Node.js SDK Live ===");

  // 1. Get an API key from local server
  const fetch = globalThis.fetch;
  const projRes = await fetch("http://localhost:8080/api/projects").then(r => r.json());
  const project = projRes.projects[0];
  if (!project) throw new Error("No project found");

  const keyRes = await fetch(`http://localhost:8080/api/projects/${project.id}/keys`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "SDK Live Test Key", scopes: "read,write,admin" })
  }).then(r => r.json());

  const apiKey = keyRes.plaintextSecret;
  console.log("Using API Key:", apiKey.substring(0, 16) + "...");

  const client = new LocalDataClient({
    apiKey,
    baseUrl: "http://localhost:8080"
  });

  // Clean up any test artifacts from prior runs
  await client.del("sdk:user:99");
  await client.del("sdk:counter");
  await client.del("sdk:batch:1");
  await client.del("sdk:batch:2");

  // 1. Test SET & GET with JSON object
  console.log("Testing SET & GET...");
  await client.set("sdk:user:99", { name: "Bob", role: "DevOps" }, { ex: 60 });
  const val = await client.get("sdk:user:99");
  console.log("GET returned:", val);
  if (val.name !== "Bob" || val.role !== "DevOps") throw new Error("Value mismatch");

  // 2. Test INCR & DECR
  console.log("Testing INCR & DECR...");
  const count = await client.incr("sdk:counter", 5);
  console.log("Counter after +5:", count);
  if (count !== 5) throw new Error("INCR mismatch");
  const countDecr = await client.decr("sdk:counter", 2);
  console.log("Counter after -2:", countDecr);
  if (countDecr !== 3) throw new Error("DECR mismatch");

  // 3. Test EXPIRE & TTL
  console.log("Testing EXPIRE & TTL...");
  await client.expire("sdk:counter", 300);
  const remainingTtl = await client.ttl("sdk:counter");
  console.log("Remaining TTL:", remainingTtl);
  if (remainingTtl <= 0) throw new Error("TTL expected > 0");

  // 4. Test MSET & MGET
  console.log("Testing MSET & MGET...");
  await client.mset({ "sdk:batch:1": "val1", "sdk:batch:2": { nested: true } });
  const mgetRes = await client.mget(["sdk:batch:1", "sdk:batch:2", "sdk:batch:none"]);
  console.log("MGET returned:", mgetRes);
  if (mgetRes["sdk:batch:1"] !== "val1" || mgetRes["sdk:batch:2"].nested !== true || mgetRes["sdk:batch:none"] !== null) {
    throw new Error("MGET mismatch");
  }

  // 5. Test SCAN
  console.log("Testing SCAN...");
  const scan = await client.scan({ pattern: "sdk:*", count: 100 });
  console.log("Scan keys:", scan.keys);
  if (!scan.keys.includes("sdk:user:99") || !scan.keys.includes("sdk:counter")) throw new Error("Scan mismatch");

  // 6. Test DBSIZE
  console.log("Testing DBSIZE...");
  const size = await client.dbsize();
  console.log("Current DB size:", size);
  if (size < 4) throw new Error("DB size expected >= 4");

  // 7. Test PIPELINE
  console.log("Testing PIPELINE...");
  const pipeRes = await client.pipeline([
    { cmd: "SET", args: ["pipe:1", "alpha"] },
    { cmd: "SET", args: ["pipe:2", "beta"] },
    { cmd: "GET", args: ["pipe:1"] },
    { cmd: "GET", args: ["pipe:2"] }
  ]);
  console.log("Pipeline result:", pipeRes);
  if (pipeRes[2] !== "alpha" || pipeRes[3] !== "beta") throw new Error("Pipeline results mismatch");

  // 8. Test DEL
  console.log("Testing DEL...");
  await client.del("sdk:user:99");
  const afterDel = await client.get("sdk:user:99");
  console.log("After DEL, get returns:", afterDel);
  if (afterDel !== null) throw new Error("Expected null after delete");

  console.log("\n=================================================");
  console.log("🎉 ALL SDK METHODS VERIFIED WITH LIVE PLATFORM!");
  console.log("=================================================");
}

run().catch(err => {
  console.error("SDK Test failed:", err);
  process.exit(1);
});
