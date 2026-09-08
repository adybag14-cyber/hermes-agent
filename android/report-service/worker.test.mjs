import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker, {handleRequest, expireReports} from './worker.mjs';

function database() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  // Execute production SQL against real SQLite; only adapt the asynchronous D1 transport interface.
  function prepare(sql, args = []) {
    const stmt = sqlite.prepare(sql);
    return {
      bind: (...values) => prepare(sql, values),
      first: async () => stmt.get(...args) ?? null,
      run: async () => ({meta: {changes: Number(stmt.run(...args).changes)}}),
    };
  }
  return {sqlite, env: {REPORTS_DB: {prepare, batch: stmts => Promise.all(stmts.map(s => s.run()))}, RATE_HASH_SECRET: 'unit-test-salt-not-a-production-secret'}};
}

function report(overrides = {}) {
  return {schema: 1, request_id: crypto.randomUUID(), deletion_secret: 'd'.repeat(64),
    reason: 'other', message: 'A synthetic test response', notes: '', app_version: '0.13.156', edition: 'play', ...overrides};
}
function request(method, path, body, headers = {}) {
  return new Request(`https://reports.example${path}`, {method,
    headers: {'Content-Type': 'application/json', 'CF-Connecting-IP': '192.0.2.1', ...headers},
    body: body === undefined ? undefined : JSON.stringify(body)});
}

test('private receipt, duplicate handling, redaction, deletion authorization and retention use real SQL', async () => {
  const {sqlite, env} = database();
  try {
    const production = report();
    const productionResult = await worker.fetch(request('POST', '/v1/reports', production), env, {waitUntil() {}});
    assert.equal(productionResult.status, 201);
    assert.equal((await worker.fetch(request('DELETE', `/v1/reports/${production.request_id}`, {deletion_secret: production.deletion_secret}), env, {waitUntil() {}})).status, 204);
    await expireReports(env, Math.floor(Date.now() / 1000) + 30 * 86400);
    const body = report({message: 'api_key=do-not-store-this sk-test0123456789012345'});
    const first = await handleRequest(request('POST', '/v1/reports', body), env, 1000);
    assert.equal(first.status, 201);
    const receipt = await first.json();
    assert.equal(receipt.report_id, body.request_id);
    assert.equal(receipt.received, true);
    const stored = sqlite.prepare('SELECT * FROM reports WHERE id=?').get(body.request_id);
    assert.equal(stored.message.includes('do-not-store'), false);
    assert.equal(stored.message.includes('sk-test'), false);
    assert.notEqual(stored.deletion_hash, body.deletion_secret);
    assert.equal((await handleRequest(request('GET', `/v1/reports/${body.request_id}`), env, 1001)).status, 404);
    assert.equal((await handleRequest(request('POST', '/v1/reports', body), env, 1001)).status, 200);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM reports').get().n, 1);
    assert.equal((await handleRequest(request('POST', '/v1/reports', {...body, deletion_secret: 'e'.repeat(64)}), env, 1001)).status, 409);
    assert.equal((await handleRequest(request('DELETE', `/v1/reports/${body.request_id}`, {deletion_secret: 'e'.repeat(64)}), env, 1002)).status, 404);
    assert.equal((await handleRequest(request('DELETE', `/v1/reports/${body.request_id}`, {deletion_secret: body.deletion_secret}), env, 1002)).status, 204);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM reports').get().n, 0);
    await handleRequest(request('POST', '/v1/reports', report()), env, 1003);
    await expireReports(env, 1003 + 30 * 86400);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM reports').get().n, 0);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM report_rate_limits').get().n, 0);
  } finally { sqlite.close(); }
});

test('invalid, oversized, cross-origin and rate-limited submissions never falsely acknowledge delivery', async () => {
  const {sqlite, env} = database();
  try {
    for (const body of [report({message: 'x'.repeat(4001)}), report({notes: 'x'.repeat(1001)}),
      report({edition: 'invalid'}), report({reason: 'invalid'}), report({request_id: 'invalid'}),
      report({deletion_secret: 'guessable'}), {...report(), private_device_identifier: 'do-not-collect'}]) {
      assert.equal((await handleRequest(request('POST', '/v1/reports', body), env, 2000)).status, 400);
    }
    assert.equal((await handleRequest(request('POST', '/v1/reports', report(), {Origin: 'https://untrusted.example'}), env, 2000)).status, 403);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM reports').get().n, 0);
    const firstReport = report();
    for (let i = 0; i < 20; i++) assert.equal((await handleRequest(request('POST', '/v1/reports', i === 0 ? firstReport : report()), env, 2000)).status, 201);
    assert.equal((await handleRequest(request('POST', '/v1/reports', report()), env, 2000)).status, 429);
    assert.equal(sqlite.prepare('SELECT count(*) AS n FROM reports').get().n, 20);
    const rows = sqlite.prepare('SELECT bucket FROM report_rate_limits').all();
    assert.equal(JSON.stringify(rows).includes('192.0.2.1'), false);
    // Filling the report quota must not consume the user's independent deletion allowance.
    assert.equal((await handleRequest(request('DELETE', `/v1/reports/${firstReport.request_id}`,
      {deletion_secret: firstReport.deletion_secret}), env, 2000)).status, 204);
    const badEnv = {...env, RATE_HASH_SECRET: undefined};
    assert.equal((await handleRequest(request('POST', '/v1/reports', report()), badEnv, 2000)).status, 503);
  } finally { sqlite.close(); }
});
