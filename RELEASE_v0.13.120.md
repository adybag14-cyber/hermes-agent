# Hermes Agent Fork v0.13.120

This release supersedes v0.13.119 with clearer endpoint debugging and a simpler
chat layout while preserving the existing Hermes chat, history, voice, image,
quick-action, native tool, and dashboard functionality.

## Android

- Shows endpoint connection progress before sending, including the selected
  host, model, HTTP response code, and first live SSE frame.
- Surfaces failed SSE response details with the HTTP message and a compact body
  snippet instead of silently ending the chat.
- Retries OpenAI-compatible endpoints with a non-stream chat completion when the
  SSE stream fails, then records either the recovered assistant text or both
  failure reasons.
- Keeps image attachments, camera capture, voice input, history, display-mode
  switching, TTS, signal quick actions, and native tool-calling behavior intact.
- Simplifies the chat screen layout with a compact header, denser status banner,
  shorter welcome state, wider readable message bubbles, and a lower-profile
  composer whose advanced actions stay in the action tray.
- Improves endpoint status detection so stream, HTTP, and non-stream fallback
  messages render as visible endpoint diagnostics.

## Web Dashboard

- Adds a visible chat endpoint banner for WebSocket connect, close, and backend
  error states instead of only writing a quiet terminal session-ended marker.

## Release

- Publishes signed universal APK and AAB artifacts for the Hermes Agent Fork
  Android package `com.mobilefork.hermesagent`.
