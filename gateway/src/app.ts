import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import path from 'node:path';

import express, { Request, RequestHandler } from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import compression from 'compression';
import cors from 'cors';
import pinoHttp from 'pino-http';
import axios from 'axios';
import { expressjwt, GetVerificationKey } from 'express-jwt';
import jwksRsa from 'jwks-rsa';
import { ProxyAgent } from 'proxy-agent';
import docsRoute from './routes/docs.js';
import { errorHandler } from './utils/error-handler.js';
import { proxyHandler } from './utils/proxy-wrapper.js';

const WINDOW_MS = 60_000;
const DATA_CACHE_TTL_MS = Number(process.env.DATA_CACHE_TTL_MS ?? 60_000);
type CacheEntry = {
  timestamp: number;
  body: unknown;
  etag?: string;
};
const dataCache = new Map<string, CacheEntry>();
const app = express();
const JAVA_API = process.env.JAVA_API_URL || 'http://localhost:8080';
const AUTH_ENABLED = process.env.AUTH_ENABLED === 'true';
const ADMIN_SCOPE = process.env.ADMIN_SCOPE ?? 'admin:reindex';
const ADMIN_ROLE = process.env.ADMIN_ROLE;
const ADMIN_ROLE_CLAIM = process.env.ADMIN_ROLE_CLAIM ?? 'roles';
const parsePort = (value: string | undefined, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};
const HTTPS_PORT = parsePort(process.env.HTTPS_PORT, 8443);
const HTTP_PORT = parsePort(process.env.HTTP_PORT, 8080);
const PUBLIC_HTTPS_PORT = parsePort(process.env.PUBLIC_HTTPS_PORT, HTTPS_PORT);

type HttpMode = 'redirect' | 'serve' | 'off';
const parseHttpMode = (): HttpMode => {
  const mode = process.env.HTTP_MODE?.trim().toLowerCase();
  if (mode === 'redirect' || mode === 'serve' || mode === 'off') {
    return mode;
  }
  const legacy = process.env.REDIRECT_HTTP;
  if (legacy) {
    return legacy.toLowerCase() === 'false' ? 'serve' : 'redirect';
  }
  return 'redirect';
};

const HTTP_MODE = parseHttpMode();
const SSL_KEY_PATH = process.env.SSL_KEY_PATH ?? path.resolve(process.cwd(), '../certs/server.key');
const SSL_CERT_PATH = process.env.SSL_CERT_PATH ?? path.resolve(process.cwd(), '../certs/server.crt');
const limiter = rateLimit({
  windowMs: WINDOW_MS,
  max: 120,
  standardHeaders: true,
  legacyHeaders: true,
  handler: (req, res) => {
    const windowSeconds = Math.ceil(WINDOW_MS / 1000);
    res.setHeader('Retry-After', windowSeconds.toString());
    res.status(429).json({ error: 'Too Many Requests' });
  }
});

const parseIfNoneMatch = (header: string | string[] | undefined): string[] => {
  if (!header) return [];
  const values = Array.isArray(header) ? header : [header];
  return values
    .flatMap((value) => value.split(','))
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
};

app.disable('x-powered-by');
app.use(helmet({
  hsts: {
    maxAge: 63072000,
    includeSubDomains: true,
    preload: true,
  },
}));
app.use(cors({ origin: false }));          // locked-down; tune per UI host
app.use(compression());
app.use(express.json({ limit: '256kb' }));
app.use(limiter);
app.use((req, res, next) => {
  const rl = (req as any).rateLimit;
  if (rl) {
    const windowSeconds = Math.ceil(WINDOW_MS / 1000);
    const remaining = Math.max(rl.remaining ?? 0, 0);
    const resetEpoch = rl.resetTime ? Math.ceil(rl.resetTime.getTime() / 1000) : Math.ceil((Date.now() + WINDOW_MS) / 1000);
    res.setHeader('RateLimit', `${rl.limit};w=${windowSeconds}`);
    res.setHeader('RateLimit-Policy', `${rl.limit};w=${windowSeconds}`);
    res.setHeader('X-RateLimit-Limit', rl.limit.toString());
    res.setHeader('X-RateLimit-Remaining', remaining.toString());
    res.setHeader('X-RateLimit-Reset', resetEpoch.toString());
  }
  next();
});
app.use(pinoHttp());

app.use(docsRoute);

app.get('/health', (_, res) => res.json({ ok: true }));

type AuthenticatedRequest = Request & {
  auth?: {
    scope?: string | string[];
    permissions?: string[];
    [key: string]: unknown;
  };
};

const ensureArray = (value: unknown, spaceDelimited = false): string[] => {
  if (typeof value === 'string') {
    return spaceDelimited ? value.split(' ').map((item) => item.trim()).filter((item) => item.length > 0) : [value];
  }
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === 'string');
  }
  return [];
};

const hasAdminGrant = (req: Request): boolean => {
  const auth = (req as AuthenticatedRequest).auth;
  if (!auth) return false;

  const scopeValues = [
    ...ensureArray(auth.scope, true),
    ...ensureArray(auth.permissions),
  ];
  if (ADMIN_SCOPE && scopeValues.includes(ADMIN_SCOPE)) {
    return true;
  }

  if (ADMIN_ROLE) {
    const roleClaims = ADMIN_ROLE_CLAIM.split(',').map((claim) => claim.trim()).filter((claim) => claim.length > 0);
    return roleClaims.some((claim) => ensureArray(auth[claim]).includes(ADMIN_ROLE));
  }

  return false;
};

let authenticate: RequestHandler = (_req, _res, next) => next();

if (AUTH_ENABLED) {
  const jwksUri = process.env.OAUTH_JWKS_URI;
  const audience = process.env.OAUTH_AUDIENCE;
  const issuer = process.env.OAUTH_ISSUER;

  if (!jwksUri) {
    throw new Error('AUTH_ENABLED=true but OAUTH_JWKS_URI is not configured.');
  }
  if (!audience) {
    throw new Error('AUTH_ENABLED=true but OAUTH_AUDIENCE is not configured.');
  }
  if (!issuer) {
    throw new Error('AUTH_ENABLED=true but OAUTH_ISSUER is not configured.');
  }

  type JwksOptions = Parameters<typeof jwksRsa.expressJwtSecret>[0];
  const jwksOptions: JwksOptions = {
    cache: true,
    rateLimit: true,
    jwksUri,
  };

  const hasProxyConfig =
    typeof process.env.HTTPS_PROXY === 'string' ||
    typeof process.env.HTTP_PROXY === 'string' ||
    typeof process.env.NO_PROXY === 'string' ||
    typeof process.env.no_proxy === 'string';

  if (hasProxyConfig) {
    jwksOptions.requestAgent = new ProxyAgent();
  }

  authenticate = expressjwt({
    secret: jwksRsa.expressJwtSecret(jwksOptions) as GetVerificationKey,
    audience,
    issuer,
    algorithms: ['RS256'],
  });
}

const requireAdmin: RequestHandler = (req, res, next) => {
  if (!AUTH_ENABLED) {
    return next();
  }

  if (hasAdminGrant(req)) {
    return next();
  }

  return res
    .status(403)
    .type('application/problem+json')
    .json({
      title: 'Forbidden',
      detail: 'Access denied.',
      status: 403,
    });
};

const forwardAuthorization = (req: Request, headers: Record<string, string>) => {
  const authHeader = req.header('authorization');
  if (authHeader) {
    headers.Authorization = authHeader;
  }
};

const forwardHeaderIfString = (
  req: Request,
  headers: Record<string, string>,
  headerName: string,
  targetName = headerName,
) => {
  const value = req.header(headerName);
  if (typeof value === 'string') {
    headers[targetName] = value;
  }
};

app.use(authenticate);

app.get(
  '/',
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    forwardHeaderIfString(req, headers, 'accept', 'Accept');
    forwardAuthorization(req, headers);

    const r = await axios.get(`${JAVA_API}/`, { headers });
    res.status(r.status).send(r.data);
  }),
);

app.get(
  '/v1/ping',
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    forwardHeaderIfString(req, headers, 'accept', 'Accept');
    forwardAuthorization(req, headers);

    const r = await axios.get(`${JAVA_API}/v1/ping`, { headers });
    res.status(r.status).send(r.data);
  }),
);

app.get(
  '/v1/series/search',
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    forwardHeaderIfString(req, headers, 'accept', 'Accept');
    forwardHeaderIfString(req, headers, 'if-none-match', 'If-None-Match');
    forwardHeaderIfString(req, headers, 'if-modified-since', 'If-Modified-Since');
    forwardAuthorization(req, headers);

    const r = await axios.get(`${JAVA_API}/v1/series/search`, {
      params: req.query,
      headers,
    });

    if (r.headers.etag) {
      res.setHeader('ETag', r.headers.etag as string);
    }

    res.status(r.status).send(r.data);
  }),
);

// Proxy example: series data
app.get(
  '/v1/series/:id/data',
  proxyHandler(async (req, res) => {
    const accept = req.headers.accept ?? 'application/json';
    const cacheKey = `${req.originalUrl}|${accept}`;
    const cached = dataCache.get(cacheKey);
    const shouldCache = DATA_CACHE_TTL_MS > 0;
    const clientIfNoneMatchHeader = req.headers['if-none-match'] as string | string[] | undefined;
    const clientEtags = parseIfNoneMatch(clientIfNoneMatchHeader);
    const now = Date.now();

    if (shouldCache && cached) {
      if (now - cached.timestamp >= DATA_CACHE_TTL_MS) {
        dataCache.delete(cacheKey);
      } else {
        if (cached.etag && clientEtags.includes(cached.etag)) {
          res.setHeader('X-Cache', 'HIT');
          res.setHeader('ETag', cached.etag);
          res.status(304).end();
          return;
        }
        res.setHeader('X-Cache', 'HIT');
        if (cached.etag) res.setHeader('ETag', cached.etag);
        res.status(200).send(cached.body);
        return;
      }
    }

    res.setHeader('X-Cache', 'MISS');
    const headers: Record<string, string> = { Accept: accept };
    if (typeof clientIfNoneMatchHeader === 'string') {
      headers['If-None-Match'] = clientIfNoneMatchHeader;
    } else if (Array.isArray(clientIfNoneMatchHeader)) {
      headers['If-None-Match'] = clientIfNoneMatchHeader.join(',');
    }
    forwardAuthorization(req, headers);

    const encodedId = encodeURIComponent(req.params.id);
    const r = await axios.get(`${JAVA_API}/v1/series/${encodedId}/data`, {
      params: req.query,
      headers,
    });

    if (r.headers.etag) {
      res.setHeader('ETag', r.headers.etag);
    }
    if (shouldCache && r.status === 200) {
      dataCache.set(cacheKey, {
        timestamp: Date.now(),
        body: r.data,
        etag: r.headers.etag,
      });
    }

    res.status(r.status).send(r.data);
  }),
);

app.get(
  '/v1/series/:id',
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    forwardHeaderIfString(req, headers, 'accept', 'Accept');
    forwardHeaderIfString(req, headers, 'if-none-match', 'If-None-Match');
    forwardHeaderIfString(req, headers, 'if-modified-since', 'If-Modified-Since');
    forwardAuthorization(req, headers);

    const encodedId = encodeURIComponent(req.params.id);
    const r = await axios.get(`${JAVA_API}/v1/series/${encodedId}`, {
      params: req.query,
      headers,
    });

    if (r.headers.etag) {
      res.setHeader('ETag', r.headers.etag as string);
    }

    res.status(r.status).send(r.data);
  }),
);

app.post(
  '/v1/series/batch',
  requireAdmin,
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    if (typeof req.headers.accept === 'string') {
      headers.Accept = req.headers.accept;
    }
    if (typeof req.headers['content-type'] === 'string') {
      headers['Content-Type'] = req.headers['content-type'];
    }
    forwardAuthorization(req, headers);

    const r = await axios.post(`${JAVA_API}/v1/series/batch`, req.body, { headers });
    res.status(r.status).send(r.data);
  }),
);

app.post(
  '/v1/exports',
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    forwardHeaderIfString(req, headers, 'accept', 'Accept');
    forwardHeaderIfString(req, headers, 'content-type', 'Content-Type');
    forwardAuthorization(req, headers);

    const r = await axios.post(`${JAVA_API}/v1/exports`, req.body, { headers });
    res.status(r.status).send(r.data);
  }),
);

// Admin reindex proxy
app.post(
  '/admin/reindex',
  requireAdmin,
  proxyHandler(async (req, res) => {
    const headers: Record<string, string> = {};
    if (typeof req.headers.accept === 'string') {
      headers.Accept = req.headers.accept;
    }
    forwardAuthorization(req, headers);

    const r = await axios.post(`${JAVA_API}/admin/reindex`, req.body, { headers });
    res.status(r.status).send(r.data);
  }),
);

app.use(errorHandler);

let sslKey: Buffer;
let sslCert: Buffer;

// Load TLS key pair (fail fast if the expected files are missing).
try {
  sslKey = fs.readFileSync(SSL_KEY_PATH);
  sslCert = fs.readFileSync(SSL_CERT_PATH);
} catch (error) {
  console.error(`Failed to read TLS credentials. Expected key at ${SSL_KEY_PATH} and cert at ${SSL_CERT_PATH}.`);
  throw error;
}

const httpsServer = https.createServer(
  {
    key: sslKey,
    cert: sslCert,
    minVersion: 'TLSv1.2',
  },
  app,
);

httpsServer.listen(HTTPS_PORT, () => {
  console.log(`Gateway listening on HTTPS :${HTTPS_PORT}`);
});

if (HTTP_MODE === 'redirect') {
  const redirectApp = express();
  // Lightweight HTTP server that enforces HTTPS via permanent redirects.
  redirectApp.disable('x-powered-by');
  redirectApp.use((req, res) => {
    const hostHeader = req.headers.host ?? 'localhost';
    const hostname = hostHeader.split(':')[0] || 'localhost';
    const portSuffix = PUBLIC_HTTPS_PORT === 443 ? '' : `:${PUBLIC_HTTPS_PORT}`;
    const location = `https://${hostname}${portSuffix}${req.originalUrl}`;
    res.redirect(308, location);
  });

  http.createServer(redirectApp).listen(HTTP_PORT, () => {
    console.log(`Redirecting HTTP :${HTTP_PORT} -> HTTPS :${PUBLIC_HTTPS_PORT}`);
  });
} else if (HTTP_MODE === 'serve') {
  http.createServer(app).listen(HTTP_PORT, () => {
    console.log(`Gateway listening on HTTP :${HTTP_PORT}`);
  });
} else {
  console.log('HTTP listener disabled (HTTP_MODE=off).');
}

export default app;

