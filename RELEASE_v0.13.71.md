# Hermes Agent v0.13.71

## Android

- Adds a Tasker event plugin so Tasker profiles can react to Hermes automation
  finished, succeeded, and failed events, plus Shizuku available/unavailable
  updates.
- Event plugin updates are token-bound to the Tasker configuration and require
  a stored Tasker pass-through message id, so third-party broadcasts cannot
  spoof event payloads by guessing an automation id.
- Returns Tasker-local `%hermes_*` event variables including automation id,
  trigger, success, result text, Shizuku state, and event timestamp.

## Validation

- `:app:testDebugUnitTest --tests com.nousresearch.hermesagent.device.HermesTaskerPluginBridgeTest`
