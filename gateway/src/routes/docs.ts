import express, { Router } from 'express';
import path from 'path';
import { createProxyMiddleware } from 'http-proxy-middleware';

const router = Router();
const SERVICE = process.env.JAVA_API_URL || 'http://localhost:8080';
const docsDir = path.join(process.cwd(), 'public', 'docs');

router.use('/docs/problems', express.static(path.join(docsDir, 'problems')));
router.use('/docs', express.static(docsDir, { index: 'index.html' }));

router.use('/docs/ui', createProxyMiddleware({
  target: SERVICE,
  changeOrigin: true,
  pathRewrite: { '^/docs/ui': '/swagger-ui.html' }
}));

router.use('/docs/openapi.yaml', createProxyMiddleware({
  target: SERVICE,
  changeOrigin: true,
  pathRewrite: { '^/docs/openapi.yaml': '/v3/api-docs.yaml' }
}));

// Backward-compatible proxies for the original Swagger UI endpoints.
router.use(['/swagger-ui.html', '/swagger-ui/*'], createProxyMiddleware({
  target: SERVICE,
  changeOrigin: true,
}));

router.use(['/v3/api-docs', '/v3/api-docs/*'], createProxyMiddleware({
  target: SERVICE,
  changeOrigin: true,
}));

export default router;
