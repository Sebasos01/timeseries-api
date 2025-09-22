import { createServer } from 'node:http';

const port = Number.parseInt(process.env.PORT ?? '8081', 10);
const healthPayload = JSON.stringify({ status: 'ok' });

export function startHealthServer() {
  const server = createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(healthPayload);
      return;
    }

    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not_found' }));
  });

  return server.listen(port, () => {
    // keep logs minimal for the placeholder server; full logging arrives in Phase 1.
    process.stdout.write(`gateway listening on http://localhost:${port}\n`);
  });
}

if (process.env.NODE_ENV !== 'test') {
  startHealthServer();
}
