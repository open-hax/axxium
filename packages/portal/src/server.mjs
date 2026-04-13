import http from 'REDACTED_SECRET:http';
import fs from 'REDACTED_SECRET:fs';
import path from 'REDACTED_SECRET:path';
import { fileURLToPath } from 'REDACTED_SECRET:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = parseInt(process.env.PORTAL_PORT || '8799', 10);
const HOST = process.env.PORTAL_HOST || '0.0.0.0';

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

const indexHtml = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf-8');

const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'promethean-portal' }));
    return;
  }

  if (req.url === '/' || req.url === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(indexHtml);
    return;
  }

  const ext = path.extname(req.url);
  const mime = MIME_TYPES[ext] || 'application/octet-stream';
  const filePath = path.join(__dirname, '..', req.url);

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const content = fs.readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': mime });
    res.end(content);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Promethean Portal listening on http://${HOST}:${PORT}`);
});
