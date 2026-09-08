const MAX_BODY_BYTES = 24_576;
const RETENTION_SECONDS = 30 * 24 * 60 * 60;
const REASONS = new Set(['child_safety', 'violence', 'sexual_content', 'hate', 'deceptive_content', 'other']);
const FIELDS = new Set(['schema', 'request_id', 'deletion_secret', 'reason', 'message', 'notes', 'app_version', 'edition']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SECRET = /^[0-9a-f]{64}$/;
const HEADERS = {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'};

function reply(status, body) {
  return new Response(body == null ? null : JSON.stringify(body), {status, headers: HEADERS});
}

export function redactSecrets(value) {
  return value
    .replace(/\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]{12,}|AIza[A-Za-z0-9_-]{20,})\b/g, '[redacted token]')
    .replace(/\b(Bearer\s+)[^\s"'<>]+/gi, '$1[redacted]')
    .replace(/\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|secret)\s*[:=]\s*[^\s,;"'<>]+/gi, '$1=[redacted]');
}

export async function digest(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(n => n.toString(16).padStart(2, '0')).join('');
}

async function readJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) {
    throw new Error('unsupported_content_type');
  }
  const length = request.headers.get('Content-Length');
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_BODY_BYTES)) throw new Error('body_too_large');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('invalid_body');
  const chunks = [];
  let size = 0;
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    size += chunk.value.length;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new Error('body_too_large');
    }
    chunks.push(chunk.value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
}

export function validateReport(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body) ||
      Object.keys(body).some(key => !FIELDS.has(key)) || Object.keys(body).length !== FIELDS.size ||
      body.schema !== 1 || !UUID.test(body.request_id) || !SECRET.test(body.deletion_secret) ||
      !REASONS.has(body.reason) || !['full', 'play'].includes(body.edition) ||
      typeof body.app_version !== 'string' || !/^0\.\d+\.\d+(?:-[a-z0-9.-]+)?$/.test(body.app_version) ||
      body.app_version.length > 48 || typeof body.message !== 'string' || body.message.length > 4000 ||
      typeof body.notes !== 'string' || body.notes.length > 1000) throw new Error('invalid_report');
  return {...body, message: redactSecrets(body.message.trim()), notes: redactSecrets(body.notes.trim())};
}

async function consumeLimit(db, bucket, limit, expires) {
  const row = await db.prepare(`INSERT INTO report_rate_limits(bucket,hits,expires_at) VALUES (?,1,?)
    ON CONFLICT(bucket) DO UPDATE SET hits=hits+1 WHERE hits < ? RETURNING hits`)
    .bind(bucket, expires, limit).first();
  return row !== null;
}

async function authorizeRate(request, env, now, purpose = 'report') {
  if (typeof env.RATE_HASH_SECRET !== 'string' || env.RATE_HASH_SECRET.length < 32) throw new Error('unconfigured');
  const day = Math.floor(now / 86400);
  // Raw client IP is used only transiently. Daily rotation prevents a stable stored identifier.
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const key = await digest(`${env.RATE_HASH_SECRET}:${day}:${ip}`);
  const deleting = purpose === 'delete';
  const prefix = deleting ? 'delete:' : '';
  if (!await consumeLimit(env.REPORTS_DB, `${prefix}client:${day}:${key}`, deleting ? 100 : 20, (day + 2) * 86400)) return false;
  return consumeLimit(env.REPORTS_DB, `${prefix}global:${day}`, deleting ? 5000 : 1000, (day + 2) * 86400);
}

export async function handleRequest(request, env, now = Math.floor(Date.now() / 1000)) {
  const url = new URL(request.url);
  if (url.protocol !== 'https:') return reply(400, {error: 'https_required'});
  if (request.method === 'GET' && url.pathname === '/health') return reply(200, {service: 'hermes-content-reports', schema: 1});
  // No public report retrieval, directory, dashboard or cross-origin browser access.
  if (request.headers.has('Origin')) return reply(403, {error: 'browser_origin_not_supported'});
  if (request.method === 'POST' && url.pathname === '/v1/reports') {
    let report;
    try { report = validateReport(await readJson(request)); }
    catch { return reply(400, {error: 'invalid_report'}); }
    try {
      if (!await authorizeRate(request, env, now)) return reply(429, {error: 'report_limit_reached'});
      const deletionHash = await digest(report.deletion_secret);
      const existing = await env.REPORTS_DB.prepare('SELECT deletion_hash,expires_at FROM reports WHERE id=?')
        .bind(report.request_id).first();
      if (existing) {
        if (existing.deletion_hash !== deletionHash) return reply(409, {error: 'report_id_conflict'});
        return reply(200, {received: true, report_id: report.request_id, expires_at: existing.expires_at});
      }
      const expiry = now + RETENTION_SECONDS;
      await env.REPORTS_DB.prepare(`INSERT INTO reports
        (id,reason,message,notes,app_version,edition,deletion_hash,received_at,expires_at)
        VALUES (?,?,?,?,?,?,?,?,?)`).bind(report.request_id, report.reason, report.message, report.notes,
          report.app_version, report.edition, deletionHash, now, expiry).run();
      return reply(201, {received: true, report_id: report.request_id, expires_at: expiry});
    } catch { return reply(503, {error: 'report_service_unavailable'}); }
  }
  const deletion = /^\/v1\/reports\/([0-9a-f-]+)$/.exec(url.pathname);
  if (request.method === 'DELETE' && deletion && UUID.test(deletion[1])) {
    let body;
    try {
      body = await readJson(request);
      if (!body || Object.keys(body).length !== 1 || !SECRET.test(body.deletion_secret)) throw new Error('invalid');
    } catch { return reply(400, {error: 'invalid_deletion_request'}); }
    try {
      if (!await authorizeRate(request, env, now, 'delete')) return reply(429, {error: 'deletion_limit_reached'});
      const result = await env.REPORTS_DB.prepare('DELETE FROM reports WHERE id=? AND deletion_hash=?')
        .bind(deletion[1], await digest(body.deletion_secret)).run();
      return result.meta.changes > 0 ? reply(204, null) : reply(404, {error: 'report_not_found'});
    } catch { return reply(503, {error: 'report_service_unavailable'}); }
  }
  return reply(404, {error: 'not_found'});
}

export async function expireReports(env, now = Math.floor(Date.now() / 1000)) {
  await env.REPORTS_DB.batch([
    env.REPORTS_DB.prepare('DELETE FROM reports WHERE expires_at <= ?').bind(now),
    env.REPORTS_DB.prepare('DELETE FROM report_rate_limits WHERE expires_at <= ?').bind(now),
  ]);
}

export default {
  fetch(request, env) { return handleRequest(request, env); },
  async scheduled(_event, env, context) { context.waitUntil(expireReports(env)); },
};
