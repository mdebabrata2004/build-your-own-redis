export interface ClientConfig {
  /**
   * The secret API key (starts with ld_live_...).
   * Defaults to process.env.LOCALDATA_API_KEY.
   */
  apiKey?: string;

  /**
   * Base URL of the REST API gateway.
   * Defaults to process.env.LOCALDATA_URL or http://localhost:8080.
   */
  baseUrl?: string;

  /**
   * Maximum request timeout in milliseconds.
   * Defaults to 5000.
   */
  timeoutMs?: number;

  /**
   * Maximum number of retry attempts on rate limits or transient errors.
   * Defaults to 3.
   */
  maxRetries?: number;

  /**
   * Initial exponential backoff delay in milliseconds.
   * Defaults to 200.
   */
  retryDelayMs?: number;
}

export interface SetOptions {
  /**
   * Set the specified expire time in seconds.
   */
  ex?: number;

  /**
   * Set the specified expire time in milliseconds.
   */
  px?: number;

  /**
   * Only set the key if it does not already exist.
   */
  nx?: boolean;

  /**
   * Only set the key if it already exists.
   */
  xx?: boolean;
}

export interface ScanOptions {
  cursor?: string;
  pattern?: string;
  count?: number;
}

export interface ScanResult {
  cursor: string;
  keys: string[];
}

export interface PipelineCommand {
  cmd: "GET" | "SET" | "DEL" | "INCR" | "EXISTS";
  args: (string | number)[];
}
