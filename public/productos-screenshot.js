(function() {
  if (window.__PRODUCTOS_SCREENSHOT_INIT) return;
  window.__PRODUCTOS_SCREENSHOT_INIT = true;

  // Pinned version so a sandbox restored from a snapshot always loads the
  // same engine. Update deliberately, not by floating @latest.
  var SNAPDOM_URL = "https://cdn.jsdelivr.net/npm/@zumer/snapdom/dist/snapdom.js";
  var loaderPromise = null;

  function loadSnapdom() {
    if (window.snapdom) return Promise.resolve(window.snapdom);
    if (loaderPromise) return loaderPromise;
    loaderPromise = new Promise(function(resolve, reject) {
      var s = document.createElement("script");
      s.src = SNAPDOM_URL;
      s.crossOrigin = "anonymous";
      s.onload = function() {
        if (window.snapdom) resolve(window.snapdom);
        else reject(new Error("snapdom loaded but global is missing"));
      };
      s.onerror = function() {
        loaderPromise = null;
        reject(new Error("Failed to load snapdom (CDN unreachable or blocked by CSP)"));
      };
      document.head.appendChild(s);
    });
    return loaderPromise;
  }

  function reply(source, payload) {
    var target = source || window.parent;
    try { target.postMessage(payload, "*"); }
    catch (_) { try { window.parent.postMessage(payload, "*"); } catch (__) {} }
  }

  function shouldSkipForCapture(el) {
    if (!el) return false;
    var id = el.id;
    if (id === "productos-inspector-overlay") return true;
    if (id === "productos-inspector-highlights") return true;
    if (el.getAttribute && el.getAttribute("data-productos-skip-shot") === "1") return true;
    return false;
  }

  // SnapDOM doesn't have an ignoreElements hook, so we hide our injected UI
  // for the duration of the capture and restore inline display afterwards.
  function hideInjectedOverlays() {
    var nodes = document.querySelectorAll(
      "#productos-inspector-overlay, #productos-inspector-highlights, [data-productos-skip-shot='1']"
    );
    var restorers = [];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var prev = n.style.display;
      restorers.push({ node: n, prev: prev });
      n.style.display = "none";
    }
    return function restore() {
      for (var i = 0; i < restorers.length; i++) {
        restorers[i].node.style.display = restorers[i].prev;
      }
    };
  }

  window.addEventListener("message", function(e) {
    var data = e.data;
    if (!data || data.type !== "productos-screenshot-request") return;
    var requestId = (data && data.requestId) || "";

    var restoreOverlays = null;
    loadSnapdom().then(function(snapdom) {
      restoreOverlays = hideInjectedOverlays();
      // toCanvas returns a real <canvas> we can call toDataURL on. We pass
      // documentElement to capture the full viewport-equivalent layout.
      return snapdom.toCanvas(document.documentElement, {
        // Snapdom honours device pixel ratio by default; cap it so massive
        // retina pages don't produce 30MB pngs.
        scale: 1,
        // Embed external images / fonts so the canvas isn't tainted.
        embedFonts: true,
      });
    }).then(function(canvas) {
      if (restoreOverlays) restoreOverlays();
      var dataUrl = canvas.toDataURL("image/png");
      reply(e.source, {
        type: "productos-screenshot-result",
        requestId: requestId,
        dataUrl: dataUrl,
        width: canvas.width,
        height: canvas.height,
      });
    }).catch(function(err) {
      if (restoreOverlays) restoreOverlays();
      reply(e.source, {
        type: "productos-screenshot-error",
        requestId: requestId,
        error: String((err && err.message) || err || "screenshot failed"),
      });
    });
  });

  // Announce readiness so parents that want to enable/disable the button can.
  try {
    window.parent.postMessage({ type: "productos-screenshot-ready" }, "*");
  } catch (_) {}
})();
