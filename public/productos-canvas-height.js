(function(){
  var lastH = 0;
  var lastW = 0;
  function documentSize() {
    var el = document.documentElement;
    var body = document.body;
    var h = Math.max(
      el ? el.scrollHeight : 0,
      body ? body.scrollHeight : 0,
      el ? el.offsetHeight : 0,
      body ? body.offsetHeight : 0
    );
    var w = Math.max(
      el ? el.scrollWidth : 0,
      body ? body.scrollWidth : 0,
      el ? el.offsetWidth : 0,
      body ? body.offsetWidth : 0
    );
    return { w: w, h: h };
  }
  function send() {
    var sz = documentSize();
    var h = sz.h;
    var w = sz.w;
    if (h < 400) h = 400;
    if (h > 20000) h = 20000;
    if (w < 1) w = document.documentElement ? document.documentElement.clientWidth : 800;
    if (w > 20000) w = 20000;
    if (h === lastH && w === lastW) return;
    lastH = h;
    lastW = w;
    try {
      window.parent.postMessage(
        { type: "productos-canvas-content-height", height: h, width: w },
        "*"
      );
    } catch (e) {}
  }
  send();
  window.addEventListener("load", function () {
    send();
    setTimeout(send, 100);
    setTimeout(send, 500);
  });
  window.addEventListener("resize", send);
  if (typeof ResizeObserver !== "undefined") {
    var ro = new ResizeObserver(function () { send(); });
    if (document.documentElement) ro.observe(document.documentElement);
    if (document.body) ro.observe(document.body);
  }
  var mo = typeof MutationObserver !== "undefined" ? new MutationObserver(function () { send(); }) : null;
  if (mo && document.documentElement) {
    mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
  }
})();