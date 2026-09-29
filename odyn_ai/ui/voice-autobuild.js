(() => {
  const BUILD_PATTERNS = [
    /\bzbuduj(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b/i,
    /\bstwórz(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b/i,
    /\butwórz(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b/i,
    /\bzrób(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\b/i
  ];

  function parse(text) {
    const clean = String(text || "").replace(/\s+/g, " ").trim();
    if (!BUILD_PATTERNS.some((pattern) => pattern.test(clean))) return null;
    const match = clean.match(/(?:zbuduj|stwórz|utwórz|zrób)(?:\s+mi)?\s+(?:aplikacj(?:ę|a)|apk(?:ę|a))\s+(.+)/i);
    const remainder = (match?.[1] || "ODYN App").trim();
    const android = /\b(android|apk)\b/i.test(clean);
    return {
      instruction: clean,
      name: remainder.replace(/\b(?:na|w|dla)\s+(?:android|web)\b.*$/i, "").trim() || "ODYN App",
      platform: android ? "android" : "web"
    };
  }

  async function autonomousBuild(text, existingAppId) {
    const command = parse(text);
    if (!command) return { executed: false, action: "chat" };

    let appId = existingAppId;
    let created = false;

    if (!appId) {
      const create = await fetch("/api/apps", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          name: command.name,
          description: command.instruction,
          agent_id: "odyn_glowny",
          language: "pl",
          platform: command.platform,
          mode: "code"
        })
      });
      if (!create.ok) throw new Error("Nie udało się utworzyć projektu.");
      const app = await create.json();
      appId = app.app_id;
      created = true;
    }

    const build = await fetch("/api/apps/" + encodeURIComponent(appId) + "/autonomous-build", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({
        instruction: command.instruction,
        timeout: 300
      })
    });
    const result = await build.json().catch(() => ({}));
    if (!build.ok) throw new Error(result.detail || "Pipeline budowy zakończył się błędem.");

    return {
      executed: true,
      action: "autonomous_build",
      app_id: appId,
      created,
      command,
      result
    };
  }

  window.ODYNVoiceAutobuild = { parse, autonomousBuild };
})();