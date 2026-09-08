import assert from 'node:assert/strict';
import {randomBytes, randomUUID} from 'node:crypto';

const origin = process.argv[2];
if (origin !== 'https://hermes-content-reports.adybag14.workers.dev') throw new Error('Unexpected live verification target');
const body = {schema: 1, request_id: randomUUID(), deletion_secret: randomBytes(32).toString('hex'),
  reason: 'other', message: 'Synthetic Hermes v156 delivery verification. No user content.',
  notes: 'This canary must be deleted by the same verification run.', app_version: '0.13.156', edition: 'play'};
async function call(method, path, data) {
  return fetch(origin + path, {method, signal: AbortSignal.timeout(15000),
    headers: {'Content-Type': 'application/json'}, body: data ? JSON.stringify(data) : undefined});
}
let deleted = false;
try {
  assert.equal((await call('GET', '/health')).status, 200);
  const created = await call('POST', '/v1/reports', body);
  assert.equal(created.status, 201, await created.clone().text());
  const receipt = await created.json();
  assert.equal(receipt.received, true);
  assert.equal(receipt.report_id, body.request_id);
  assert.equal((await call('GET', `/v1/reports/${body.request_id}`)).status, 404);
  assert.equal((await call('POST', '/v1/reports', body)).status, 200);
  assert.equal((await call('DELETE', `/v1/reports/${body.request_id}`, {deletion_secret: '0'.repeat(64)})).status, 404);
  assert.equal((await call('DELETE', `/v1/reports/${body.request_id}`, {deletion_secret: body.deletion_secret})).status, 204);
  deleted = true;
  console.log(JSON.stringify({utc: new Date().toISOString(), origin, report_id: body.request_id,
    synthetic_only: true, delivery: 'pass', duplicate: 'pass', public_read: 'denied',
    wrong_token_deletion: 'denied', correct_token_deletion: 'pass', deletion_secret_logged: false}, null, 2));
} finally {
  if (!deleted) {
    const cleanup = await call('DELETE', `/v1/reports/${body.request_id}`, {deletion_secret: body.deletion_secret});
    if (![204, 404].includes(cleanup.status)) throw new Error(`Canary cleanup requires attention: ${cleanup.status}, id=${body.request_id}`);
  }
}
