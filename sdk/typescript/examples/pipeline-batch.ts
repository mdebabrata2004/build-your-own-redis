import { LocalDataClient } from "../src/index";

/**
 * Example demonstrating batch pipelining for high-throughput operations.
 */
async function pipelineExample() {
  const client = new LocalDataClient({
    apiKey: process.env.LOCALDATA_API_KEY || "ld_live_test_dummy_key"
  });

  console.log("Submitting atomic pipeline of 5 commands in 1 network trip...");
  const results = await client.pipeline([
    { cmd: "SET", args: ["batch:k1", "val1"] },
    { cmd: "SET", args: ["batch:k2", "val2"] },
    { cmd: "INCR", args: ["batch:counter"] },
    { cmd: "GET", args: ["batch:k1"] },
    { cmd: "EXISTS", args: ["batch:k2"] }
  ]);

  console.log("Pipeline results:", results);
  // Expected: ["OK", "OK", 1, "val1", true]
}

pipelineExample().catch(console.error);
