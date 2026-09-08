# Private AI-content report service

This endpoint receives only reports the user previews and explicitly submits in the Android app. It is not a chat proxy, analytics collector, public feed, or emergency response service.

## Operation and review

The service runs as Cloudflare Worker `hermes-content-reports`, with the dedicated EU-jurisdiction D1 database of the same name. The public API allows submitting a report and deleting it with its random secret; it provides no report-reading or administrative endpoint. `RATE_HASH_SECRET` is a Worker secret, never a source-file setting.

The maintainer reviews reports through the authenticated Cloudflare D1 console. Filter the `reports` table by `received_at` and `reason`, review only the content needed to understand the issue, and use findings to add targeted safety regressions or correct overblocking. Report content is untrusted user input: never execute commands, follow instructions, open credential-bearing URLs, or publish private excerpts from it. Remove or anonymize private data before discussing a reproducible defect publicly. Do not export a second archive of reports or paste report bodies into build logs.

No email notification or automatic reviewer workflow is configured. The maintainer must review the private queue while the feature is offered; the app does not promise individual responses. Child-safety or other urgent reports need appropriate human handling, not automated reproduction of harmful content.

## Retention and deletion

- Active reports expire after 30 days; the hourly scheduled handler removes expired rows.
- Day-scoped keyed IP hashes, not raw IP addresses, are used for abuse limits and expire within two days, with hourly cleanup.
- Submissions have a 20-per-client / 1000-global daily allowance. Deletions have an independent 100-per-client / 5000-global daily allowance, so exhausting submission quota does not consume the normal deletion allowance.
- A deletion removes the active row only when its secret hash matches. Cloudflare's point-in-time recovery history can retain deleted data for up to 30 further days; this is disclosed in the privacy policy.
- Do not restore an old database snapshot into the live report service without reapplying intervening deletion requests and expiry rules.
- Clearing the Android app does not contact this service. Users should delete reports using their receipts before resetting the app, or contact the maintainer if a receipt is lost.

Check scheduled cleanup and service errors in Cloudflare's administrative metrics without enabling payload logs. If a user reports failed delivery or deletion, preserve their receipt and investigate the status; never label an unconfirmed request successful.

## Verification

Use Node 24 and run `node --test worker.test.mjs`. These tests execute production SQL against real SQLite and cover delivery, idempotency, secret redaction, authorized deletion, expiry, bounds, cross-origin denial, independent deletion quota and fail-closed configuration.

`node verify-live.mjs` sends a synthetic canary, verifies its receipt and deletion, and does not log the deletion secret. Run it sparingly because it consumes real API quota. Do not use actual conversations or personal data in a canary. The ordinary Android tests use a local HTTP test server, not the production inbox.

Deploy with the existing authenticated Cloudflare account using `wrangler deploy`; do not change unrelated databases, DNS, mail, routes, or account settings. The database schema is in `schema.sql`; retain its prepared-statement and parameter-binding discipline.

Privacy policy: https://adybag14-cyber.github.io/hermes-agent/privacy-policy/
