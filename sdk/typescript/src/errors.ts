export class LocalDataError extends Error {
  public readonly code: string;
  public readonly statusCode?: number;

  constructor(message: string, code: string = "LOCALDATA_ERROR", statusCode?: number) {
    super(message);
    this.name = "LocalDataError";
    this.code = code;
    this.statusCode = statusCode;
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class AuthenticationError extends LocalDataError {
  constructor(message: string = "Invalid, expired, or missing API key") {
    super(message, "AUTHENTICATION_ERROR", 401);
    this.name = "AuthenticationError";
  }
}

export class ForbiddenError extends LocalDataError {
  constructor(message: string = "API key lacks required permission scope") {
    super(message, "FORBIDDEN", 403);
    this.name = "ForbiddenError";
  }
}

export class RateLimitError extends LocalDataError {
  public readonly retryAfterMs: number;

  constructor(message: string, retryAfterMs: number = 1000) {
    super(message, "RATE_LIMITED", 429);
    this.name = "RateLimitError";
    this.retryAfterMs = retryAfterMs;
  }
}

export class PayloadTooLargeError extends LocalDataError {
  constructor(message: string = "Payload size exceeds maximum allowed limit") {
    super(message, "PAYLOAD_TOO_LARGE", 413);
    this.name = "PayloadTooLargeError";
  }
}
