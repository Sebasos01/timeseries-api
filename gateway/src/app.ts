import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import compression from 'compression';
import cors from 'cors';
import pinoHttp from 'pino-http';
import axios from 'axios';
import docsRoute from './routes/docs.js';

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

// Simple API-key auth (replace with JWT validation if needed)
app.use((req, res, next) => {
  const key = req.header('x-api-key');
  if (!key || key !== process.env.API_KEY) return res.status(401).json({ error: 'Unauthorized' });
  next();
});

app.get('/health', (_, res) => res.json({ ok: true }));

// Proxy example: series data
app.get('/v1/series/:id/data', async (req, res) => {
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
        return res.status(304).end();
      }
      res.setHeader('X-Cache', 'HIT');
      if (cached.etag) res.setHeader('ETag', cached.etag);
      return res.status(200).send(cached.body);
    }
  }

  res.setHeader('X-Cache', 'MISS');
  try {
    const headers: Record<string, string> = { Accept: accept };
    if (typeof clientIfNoneMatchHeader === 'string') {
      headers['If-None-Match'] = clientIfNoneMatchHeader;
    } else if (Array.isArray(clientIfNoneMatchHeader)) {
      headers['If-None-Match'] = clientIfNoneMatchHeader.join(',');
    }

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
    return res.status(r.status).send(r.data);
  } catch (e: any) {
    const { response } = e;
    if (response) {
      if (response.headers?.etag) {
        res.setHeader('ETag', response.headers.etag);
      }
      return res.status(response.status).send(response.data);
    }
    return res.status(502).json({ error: 'Bad Gateway' });
  }
});

// Admin reindex proxy
app.post('/admin/reindex', async (_req, res) => {
  try {
    const r = await axios.post(`${JAVA_API}/admin/reindex`);
    res.status(r.status).send(r.data);
  } catch (e: any) {
    if (e.response) res.status(e.response.status).send(e.response.data);
    else res.status(502).json({ error: 'Bad Gateway' });
  }
});

app.listen(8081, () => console.log('Gateway listening on :8081'));

export default app;

