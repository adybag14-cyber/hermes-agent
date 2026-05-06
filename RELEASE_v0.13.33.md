# Hermes Agent v0.13.33

This Android-focused release adds the next Tasker-style profile slice for
native Hermes automations.

## Android Tasker Sensor Automation

- Adds explicit saved sensor trigger records through `android_automation_tool`
  and `run_sensor_trigger`.
- Sensor trigger records can match sensor type/name, event or gesture label,
  value name/axis, and numeric min/max value filters.
- Sensor events expose Tasker-style variables: `%SENSOR`, `%SENSOR_TYPE`,
  `%SENSOR_NAME`, `%SENSOR_EVENT`, `%SENSOR_VALUE`, `%SENSOR_VALUE_NAME`,
  `%SENSOR_UNIT`, and `%SENSOR_ACCURACY`.
- Keeps sensor support explicit-dispatch only for this release. Hermes does not
  start a hidden background sensor observer; provider-backed sensor monitoring
  remains a future Tasker-parity step.

## Validation

- Added unit coverage for saved sensor trigger creation, matching, variable
  exposure, and invalid range rejection.
- Added connected Android coverage proving a matching sensor event can run a
  saved file-write automation in the real app workspace.
