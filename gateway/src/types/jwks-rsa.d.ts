declare module 'jwks-rsa' {
  import type { GetVerificationKey } from 'express-jwt';

  interface Options {
    cache?: boolean;
    cacheMaxEntries?: number;
    cacheMaxAge?: number;
    jwksUri: string;
    rateLimit?: boolean;
    jwksRequestsPerMinute?: number;
    requestAgent?: unknown;
  }

  export function expressJwtSecret(options: Options): GetVerificationKey;

  const jwksRsa: {
    expressJwtSecret: typeof expressJwtSecret;
  };

  export default jwksRsa;
}
