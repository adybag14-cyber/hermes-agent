# Remote ChatGPT Web Auth over VNC

For a remote Linux box that already has `Xvfb`, `openbox`, `x11vnc`, `websockify`, and a Chromium-compatible browser installed, use:

```bash
scripts/chatgpt-web-vnc-auth.sh start --public-host <public-ip-or-hostname>
```

That helper:

- starts an isolated Xvfb display
- launches `openbox`
- exposes the display through `x11vnc`
- serves noVNC with `websockify`
- runs `hermes auth browser chatgpt-web --keep-open`

Useful follow-ups:

```bash
scripts/chatgpt-web-vnc-auth.sh status
scripts/chatgpt-web-vnc-auth.sh stop
```

The script writes runtime state under `~/.hermes/remote-chatgpt-web-auth/` by default, including:

- `session.env` with the live URL, ports, and generated VNC password
- `run/*.pid` with tracked process IDs
- `logs/*.log` with Xvfb/openbox/x11vnc/websockify/auth logs

Notes:

- `hermes auth browser chatgpt-web` still needs a Chromium-compatible browser. The helper does not replace the browser selection logic in Hermes.
- `--password` sets a fixed VNC password. If omitted, the helper generates one.
- `--public-host` only affects the printed URL. It does not change firewall or cloud security-list rules.
- If the VM's public port is blocked, tunnel locally instead:

```bash
ssh -L 16080:127.0.0.1:6080 <user>@<host>
```

Then open `http://127.0.0.1:16080/vnc.html` in your local browser.
