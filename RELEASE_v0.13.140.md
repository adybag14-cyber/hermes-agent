# Hermes Agent Fork v0.13.140

Full release: official **openai/codex** ChatGPT/Codex OAuth path in-app.

## Codex / ChatGPT OAuth (openai/codex parity)

Primary browser flow from `codex-rs/login`:
- Client ID `app_EMoamEEZ73f0CkXaXp7hrann`
- Authorize `https://auth.openai.com/oauth/authorize` with official scope, PKCE, simplified-flow flags
- Callback `http://localhost:1455/auth/callback` (fallback port **1457**)
- Token `https://auth.openai.com/oauth/token`
- Opens in Hermes **in-app WebView**

Device-code fallback (official paths):
- `POST …/api/accounts/deviceauth/usercode`
- User page `https://auth.openai.com/codex/device`
- Poll `…/deviceauth/token` → exchange via `…/deviceauth/callback`

## Also includes (from 0.13.139)

- xAI Grok SuperGrok PKCE OAuth
- Nous Portal device-code login
- Host Termux-style `pkg` suite updates
- Leaner chat load / readiness strip

## Version

- 0.13.140 / versionCode 144090
- Package: com.mobilefork.hermesagent
