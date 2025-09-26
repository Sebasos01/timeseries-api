declare module 'express-jwt' {
  import type { Request, RequestHandler } from 'express';

  export type GetVerificationKey = (req: Request, token: any) => Promise<string | Buffer> | string | Buffer;

  export interface Params {
    secret: string | Buffer | GetVerificationKey;
    audience?: string | string[];
    issuer?: string | string[];
    algorithms?: string[];
    requestProperty?: string;
  }

  export function expressjwt(params: Params): RequestHandler;
}
