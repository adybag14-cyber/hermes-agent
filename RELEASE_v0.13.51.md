## Tasker Clipboard Automation

This release extends Hermes' safe Tasker parity surface with Android clipboard
automation.

- Adds saved `create_clipboard_task` records that set Android clipboard text.
- Expands saved Hermes variables in clipboard text and clip labels at run time.
- Imports Tasker XML `Set Clipboard` actions into disabled-by-default Hermes
  clipboard automations.
- Keeps arbitrary Tasker Java, JavaScript, plugins, and scenes outside the safe
  import subset.
