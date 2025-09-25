import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import compression from 'compression';
import cors from 'cors';
import pinoHttp from 'pino-http';
import axios from 'axios';
import docsRoute from './routes/docs.js';

const WINDOW_MS = 60_000;
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
  try {
    const r = await axios.get(`${JAVA_API}/v1/series/${encodeURIComponent(req.params.id)}/data`,
      { params: req.query, headers: { Accept: req.headers.accept ?? 'application/json' } });
    // Mirror ETag/304, cache headers etc.
    if (r.headers.etag) res.setHeader('ETag', r.headers.etag);
    res.status(r.status).send(r.data);
  } catch (e: any) {
    if (e.response) res.status(e.response.status).send(e.response.data);
    else res.status(502).json({ error: 'Bad Gateway' });
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
