import { Redis } from '@upstash/redis';

const redis = Redis.fromEnv();

const REQUEST_TTL = 120;
const QUEUE_TTL = 900;

function send(res, status, payload) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.end(JSON.stringify(payload));
}

async function readBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  if (typeof req.body === 'string' && req.body.length) {
    try {
      return JSON.parse(req.body);
    } catch (_) {
      return {};
    }
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch (_) {
    return {};
  }
}

function normalizeCode(value) {
  return String(value || '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 8);
}

function parseJson(value) {
  if (value == null) return null;
  if (typeof value === 'object') return value;
  try {
    return JSON.parse(value);
  } catch (_) {
    return null;
  }
}

function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 9);
}

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return send(res, 204, {});
  }

  const url = new URL(req.url, 'http://localhost');
  const route = url.pathname.replace(/^\/api\/relay\/?/, '').replace(/\/$/, '');

  try {
    if (route === 'health') {
      return send(res, 200, { ok: true, at: Date.now() });
    }

    // Игрок ставит запрос в очередь ведущему.
    if (route === 'enqueue' && req.method === 'POST') {
      const body = await readBody(req);
      const code = normalizeCode(body.code);
      if (!code) return send(res, 400, { ok: false, error: 'Нет кода комнаты' });

      const id = newId();
      const request = {
        id,
        method: String(body.method || 'GET').toUpperCase(),
        path: String(body.path || '/'),
        body: typeof body.body === 'string' ? body.body : ''
      };
      await redis.set('req:' + id, JSON.stringify(request), { ex: REQUEST_TTL });
      await redis.lpush('queue:' + code, id);
      await redis.expire('queue:' + code, QUEUE_TTL);
      return send(res, 200, { ok: true, id });
    }

    // Игрок забирает ответ ведущего.
    if (route === 'result' && req.method === 'GET') {
      const id = String(url.searchParams.get('id') || '');
      if (!id) return send(res, 400, { ok: false, error: 'Нет id' });
      const raw = await redis.get('res:' + id);
      const stored = parseJson(raw);
      if (!stored) return send(res, 200, { pending: true });
      await redis.del('res:' + id);
      return send(res, 200, {
        pending: false,
        status: stored.status || 200,
        body: stored.body || ''
      });
    }

    // Телефон ведущего забирает пачку запросов.
    if (route === 'poll' && req.method === 'POST') {
      const body = await readBody(req);
      const code = normalizeCode(body.code);
      if (!code) return send(res, 400, { ok: false, error: 'Нет кода комнаты' });

      const ids = [];
      for (let i = 0; i < 25; i += 1) {
        const id = await redis.rpop('queue:' + code);
        if (!id) break;
        ids.push(String(id));
      }

      const requests = [];
      for (const id of ids) {
        const raw = await redis.get('req:' + id);
        const request = parseJson(raw);
        if (request) {
          requests.push(request);
          await redis.del('req:' + id);
        }
      }
      return send(res, 200, { ok: true, requests });
    }

    // Телефон ведущего присылает ответы.
    if (route === 'reply' && req.method === 'POST') {
      const body = await readBody(req);
      const code = normalizeCode(body.code);
      if (!code) return send(res, 400, { ok: false, error: 'Нет кода комнаты' });
      const responses = Array.isArray(body.responses) ? body.responses : [];
      for (const response of responses.slice(0, 40)) {
        const id = String(response.id || '');
        if (!id) continue;
        await redis.set(
          'res:' + id,
          JSON.stringify({
            status: Number(response.status) || 200,
            body: typeof response.body === 'string' ? response.body : ''
          }),
          { ex: REQUEST_TTL }
        );
      }
      return send(res, 200, { ok: true });
    }

    return send(res, 404, { ok: false, error: 'Неизвестный маршрут реле' });
  } catch (error) {
    return send(res, 500, {
      ok: false,
      error: error && error.message ? error.message : 'Ошибка реле'
    });
  }
}
