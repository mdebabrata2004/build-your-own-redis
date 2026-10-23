import {
  AuthenticationError,
  ForbiddenError,
  LocalDataError,
  PayloadTooLargeError,
  RateLimitError
} from "./errors";
import { ClientConfig, PipelineCommand, ScanOptions, ScanResult, SetOptions } from "./types";

export * from "./errors";
export * from "./types";

/**
 * Production-ready TypeScript / JavaScript client for the LocalData platform.
 */
export class LocalDataClient {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly retryDelayMs: number;

  constructor(config: ClientConfig = {}) {
    const key = config.apiKey || (typeof process !== "undefined" ? process.env?.LOCALDATA_API_KEY : undefined);
    if (!key) {
      throw new AuthenticationError("Missing required API key. Pass 'apiKey' or set LOCALDATA_API_KEY environment variable.");
    }

    this.apiKey = key.trim();
    const rawUrl = config.baseUrl || (typeof process !== "undefined" ? process.env?.LOCALDATA_URL : undefined) || "http://localhost:8080";
    this.baseUrl = rawUrl.replace(/\/+$/, "");
    this.timeoutMs = config.timeoutMs ?? 5000;
    this.maxRetries = config.maxRetries ?? 3;
    this.retryDelayMs = config.retryDelayMs ?? 200;
  }

  /**
   * Retrieves the value of a key. Automatically parses JSON strings into structured objects.
   */
  async get<T = any>(key: string): Promise<T | null> {
    if (!key) throw new Error("Key cannot be empty");
    try {
      const res = await this.request<{ success: boolean; value: string }>(
        `/v1/keys/${encodeURIComponent(key)}`,
        { method: "GET" }
      );
      if (res.value === null || res.value === undefined) return null;
      return this.deserialize<T>(res.value);
    } catch (err: any) {
      if (err instanceof LocalDataError && err.code === "KEY_NOT_FOUND") {
        return null;
      }
      throw err;
    }
  }

  /**
   * Sets a key to hold a given value with optional expiration and conditional NX/XX flags.
   */
  async set(key: string, value: any, options: SetOptions = {}): Promise<boolean> {
    if (!key) throw new Error("Key cannot be empty");
    const serialized = this.serialize(value);

    const query = new URLSearchParams();
    if (options.ex !== undefined) query.set("ex", String(options.ex));
    if (options.px !== undefined) query.set("px", String(options.px));
    if (options.nx) query.set("nx", "true");
    if (options.xx) query.set("xx", "true");

    const qs = query.toString();
    const endpoint = `/v1/keys/${encodeURIComponent(key)}${qs ? `?${qs}` : ""}`;

    const res = await this.request<{ success: boolean }>(endpoint, {
      method: "POST",
      headers: { "Content-Type": "text/plain; charset=utf-8" },
      body: serialized
    });
    return Boolean(res.success);
  }

  /**
   * Removes the specified key. Returns true if the key existed and was deleted.
   */
  async del(key: string): Promise<boolean> {
    if (!key) throw new Error("Key cannot be empty");
    const res = await this.request<{ success: boolean; deleted: boolean }>(
      `/v1/keys/${encodeURIComponent(key)}`,
      { method: "DELETE" }
    );
    return Boolean(res.deleted);
  }

  /**
   * Increments the number stored at key by delta (default 1).
   */
  async incr(key: string, delta: number = 1): Promise<number> {
    if (!key) throw new Error("Key cannot be empty");
    const res = await this.request<{ success: boolean; value: number }>(
      `/v1/keys/${encodeURIComponent(key)}/incr?delta=${delta}`,
      { method: "POST" }
    );
    return res.value;
  }

  /**
   * Decrements the number stored at key by delta (default 1).
   */
  async decr(key: string, delta: number = 1): Promise<number> {
    return this.incr(key, -delta);
  }

  /**
   * Sets a timeout on key in seconds.
   */
  async expire(key: string, seconds: number): Promise<boolean> {
    if (!key) throw new Error("Key cannot be empty");
    const res = await this.request<{ success: boolean; applied: boolean }>(
      `/v1/keys/${encodeURIComponent(key)}/expire?seconds=${seconds}`,
      { method: "POST" }
    );
    return Boolean(res.applied);
  }

  /**
   * Returns the remaining time to live of a key that has a timeout, in seconds.
   * Returns -1 if key exists without TTL; returns -2 if key does not exist.
   */
  async ttl(key: string): Promise<number> {
    if (!key) throw new Error("Key cannot be empty");
    const res = await this.request<{ success: boolean; ttl: number }>(
      `/v1/keys/${encodeURIComponent(key)}/ttl`,
      { method: "GET" }
    );
    return res.ttl;
  }

  /**
   * Returns the values of all specified keys.
   */
  async mget<T = any>(keys: string[]): Promise<Record<string, T | null>> {
    if (!keys || !keys.length) return {};
    const res = await this.request<{ success: boolean; results: Record<string, string | null> }>(
      `/v1/mget`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ keys })
      }
    );

    const mapped: Record<string, T | null> = {};
    for (const [k, v] of Object.entries(res.results)) {
      mapped[k] = v !== null ? this.deserialize<T>(v) : null;
    }
    return mapped;
  }

  /**
   * Sets the given keys to their respective values.
   */
  async mset(entries: Record<string, any>): Promise<number> {
    if (!entries || !Object.keys(entries).length) return 0;
    const serialized: Record<string, string> = {};
    for (const [k, v] of Object.entries(entries)) {
      serialized[k] = this.serialize(v);
    }

    const res = await this.request<{ success: boolean; count: number }>(
      `/v1/mset`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ entries: serialized })
      }
    );
    return res.count;
  }

  /**
   * Iterates through keys matching pattern in the tenant database.
   */
  async scan(options: ScanOptions = {}): Promise<ScanResult> {
    const query = new URLSearchParams();
    if (options.cursor) query.set("cursor", options.cursor);
    if (options.pattern) query.set("pattern", options.pattern);
    if (options.count) query.set("count", String(options.count));

    const qs = query.toString();
    const endpoint = `/v1/scan${qs ? `?${qs}` : ""}`;

    const res = await this.request<{ success: boolean; cursor: string; keys: string[] }>(
      endpoint,
      { method: "GET" }
    );
    return {
      cursor: res.cursor,
      keys: res.keys || []
    };
  }

  /**
   * Executes a batch of commands sequentially in a single atomic network round-trip.
   */
  async pipeline(commands: PipelineCommand[]): Promise<any[]> {
    if (!commands || !commands.length) return [];
    const res = await this.request<{ success: boolean; results: any[] }>(
      `/v1/pipeline`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(commands)
      }
    );
    return res.results;
  }

  /**
   * Returns the total number of keys in the current project database.
   */
  async dbsize(): Promise<number> {
    const res = await this.request<{ success: boolean; dbsize: number }>(
      `/v1/dbsize`,
      { method: "GET" }
    );
    return res.dbsize;
  }

  /**
   * Flushes only the current project's database. Does not impact other tenants.
   */
  async flushdb(): Promise<boolean> {
    const res = await this.request<{ success: boolean }>(
      `/v1/flushdb`,
      { method: "POST" }
    );
    return Boolean(res.success);
  }

  // ──────────────────────────────────────────────────────────────
  // Internal HTTP & Retry Engine
  // ──────────────────────────────────────────────────────────────

  private async request<T>(path: string, options: RequestInit): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", `Bearer ${this.apiKey}`);

    let attempt = 0;
    while (true) {
      attempt++;
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

        const response = await fetch(url, {
          ...options,
          headers,
          signal: controller.signal
        }).finally(() => clearTimeout(timeout));

        const bodyText = await response.text();
        let json: any;
        try {
          json = bodyText ? JSON.parse(bodyText) : {};
        } catch {
          json = { raw: bodyText };
        }

        if (response.ok) {
          return json as T;
        }

        // Map status codes to typed exceptions
        const errCode = json?.error?.code || "API_ERROR";
        const errMsg = json?.error?.message || response.statusText;

        if (response.status === 401) {
          throw new AuthenticationError(errMsg);
        }
        if (response.status === 403) {
          throw new ForbiddenError(errMsg);
        }
        if (response.status === 413) {
          throw new PayloadTooLargeError(errMsg);
        }
        if (response.status === 429) {
          const retryAfterSec = parseInt(response.headers.get("Retry-After") || "1", 10);
          const waitMs = retryAfterSec * 1000;
          if (attempt <= this.maxRetries) {
            await this.sleep(waitMs);
            continue;
          }
          throw new RateLimitError(errMsg, waitMs);
        }

        throw new LocalDataError(errMsg, errCode, response.status);
      } catch (err: any) {
        if (err instanceof LocalDataError) {
          throw err;
        }

        // Network error or timeout retry
        if (attempt <= this.maxRetries) {
          const backoff = this.retryDelayMs * Math.pow(2, attempt - 1);
          await this.sleep(backoff);
          continue;
        }

        throw new LocalDataError(
          `Request to ${path} failed after ${attempt} attempts: ${err.message}`,
          "NETWORK_ERROR"
        );
      }
    }
  }

  private serialize(value: any): string {
    if (value === null || value === undefined) return "";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    return JSON.stringify(value);
  }

  private deserialize<T>(text: string): T {
    if (!text) return text as unknown as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      return text as unknown as T;
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
