# @localdata/client

Official TypeScript and JavaScript SDK for the **LocalData Managed Redis-Compatible Data Platform**.

## Installation

```bash
npm install @localdata/client
# or
pnpm add @localdata/client
# or
yarn add @localdata/client
```

## Quick Start

```typescript
import { LocalDataClient } from "@localdata/client";

// Initialize client with environment variable or explicit config
const client = new LocalDataClient({
  apiKey: process.env.LOCALDATA_API_KEY, // e.g. "ld_live_..."
  baseUrl: process.env.LOCALDATA_URL || "http://localhost:8080"
});

async function run() {
  // 1. Store structured JSON or plain text
  await client.set("user:123", { name: "Alice", role: "developer" });

  // 2. Retrieve automatically parsed object
  const user = await client.get<{ name: string; role: string }>("user:123");
  console.log("User:", user?.name);

  // 3. Key expiration (TTL)
  await client.set("session:temp", "secret-token", { ex: 300 }); // 5 minutes
  const ttl = await client.ttl("session:temp");

  // 4. Atomic counters
  const views = await client.incr("page:home:views", 1);

  // 5. Delete key
  await client.del("user:123");
}

run().catch(console.error);
```

## API Reference

### Key-Value Operations
- `client.get<T>(key: string): Promise<T | null>`
- `client.set(key: string, value: any, options?: SetOptions): Promise<boolean>`
- `client.del(key: string): Promise<boolean>`
- `client.incr(key: string, delta?: number): Promise<number>`
- `client.decr(key: string, delta?: number): Promise<number>`
- `client.expire(key: string, seconds: number): Promise<boolean>`
- `client.ttl(key: string): Promise<number>`

### Batch Operations
- `client.mget<T>(keys: string[]): Promise<Record<string, T | null>>`
- `client.mset(entries: Record<string, any>): Promise<number>`
- `client.pipeline(commands: PipelineCommand[]): Promise<any[]>`
- `client.scan(options?: ScanOptions): Promise<ScanResult>`

### Database Operations
- `client.dbsize(): Promise<number>`
- `client.flushdb(): Promise<boolean>` (scoped strictly to current project)

## Error Handling

The client throws typed errors that you can catch and inspect:

```typescript
import { 
  LocalDataClient, 
  AuthenticationError, 
  RateLimitError, 
  ForbiddenError 
} from "@localdata/client";

try {
  await client.set("key", "val");
} catch (err) {
  if (err instanceof RateLimitError) {
    console.warn(`Rate limit reached. Retry after ${err.retryAfterMs}ms`);
  } else if (err instanceof AuthenticationError) {
    console.error("API key is invalid or revoked.");
  } else if (err instanceof ForbiddenError) {
    console.error("Missing write permission scope.");
  }
}
```
