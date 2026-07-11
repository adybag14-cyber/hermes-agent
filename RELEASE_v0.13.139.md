# Hermes Agent Fork v0.13.139

Full feature release: complete OAuth/subscription sign-in paths and performance polish (no feature removals).

## OAuth / subscriptions (complete)

- **xAI Grok OAuth (SuperGrok)**: OIDC discovery + PKCE + 127.0.0.1:56121 loopback; authorize in Hermes in-app WebView.
- **ChatGPT / Codex device code**: OpenAI device auth usercode + poll + token exchange; verification page in-app.
- **Nous Portal device code**: device/code + token poll; verification URL opens in-app.
- **OpenRouter**: custom-scheme WebView first; loopback external fallback.
- Provider setup (Qwen, Zhipu, BigModel CN, Grok API key, etc.) prefers in-app browser.

## Host Linux suite

- In-app Termux-style `pkg` / `linux_host_pkg_tool` for proot/proot-distro updates without APK rebuild.

## Performance

- Async chat history load; conversation + settings caches
- Throttled stream disk writes
- Linux suite process cache (skip redundant shell probes)
- Smarter readiness strip (direct-provider ready without forcing Python)

## Version

- 0.13.139 / versionCode 143990
- Package: com.mobilefork.hermesagent
