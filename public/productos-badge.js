(function() {
  // Double-load guard. Deliberately NOT "is an element with our id present":
  // agent-authored badges claim that same id, which made the platform badge
  // silently give up — leaving the project with a one-off badge, or none.
  // Ownership is tracked on window instead.
  if (window.__productosBadgeLoaded) return;
  window.__productosBadgeLoaded = true;

  // Check if dismissed
  try {
    if (localStorage.getItem('productos-badge-dismissed') === 'true') return;
  } catch(e) {}

  // Check if parent requested badge hidden via URL param
  try {
    var params = new URLSearchParams(window.location.search);
    if (params.get('productos_hide_badge') === '1') return;
  } catch(e) {}

  // Wait for body
  function inject() {
    if (!document.body) {
      setTimeout(inject, 50);
      return;
    }

    // Replace any foreign badge sitting in our slot so every project shows the
    // SAME default badge rather than a hand-rolled variant.
    var prior = document.getElementById('productos-badge');
    if (prior) {
      if (prior.getAttribute('data-productos-badge') === 'platform') return;
      prior.parentNode && prior.parentNode.removeChild(prior);
    }

    var badge = document.createElement('div');
    badge.id = 'productos-badge';
    badge.setAttribute('data-productos-badge', 'platform');
    badge.innerHTML = `
      <style>
        @keyframes productos-badge-fade-in {
          from { opacity: 0; transform: translateY(10px); }
          to { opacity: 1; transform: translateY(0); }
        }
        #productos-badge {
          position: fixed;
          bottom: 16px;
          right: 16px;
          z-index: 2147483640;
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
          animation: productos-badge-fade-in 0.3s ease;
        }
        #productos-badge a {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 8px 12px;
          background: linear-gradient(135deg, #18181b 0%, #27272a 100%);
          border: 1px solid rgba(255,255,255,0.1);
          border-radius: 8px;
          color: #fafafa;
          font-size: 13px;
          font-weight: 500;
          text-decoration: none;
          box-shadow: 0 4px 12px rgba(0,0,0,0.15), 0 0 0 1px rgba(255,255,255,0.05);
          transition: all 0.2s ease;
        }
        #productos-badge a:hover {
          transform: translateY(-1px);
          box-shadow: 0 6px 16px rgba(0,0,0,0.2);
        }
        #productos-badge .prefix {
          color: #a1a1aa;
        }
        #productos-badge .brand {
          display: flex;
          align-items: center;
          gap: 4px;
          color: #fafafa;
        }
        #productos-badge .close {
          position: absolute;
          top: -6px;
          right: -6px;
          width: 18px;
          height: 18px;
          padding: 0;
          background: #3f3f46;
          border: 1px solid rgba(255,255,255,0.1);
          border-radius: 50%;
          color: #a1a1aa;
          font-size: 12px;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        #productos-badge .close:hover {
          background: #52525b;
          color: #fafafa;
        }
      </style>
      <a href="https://productos.dev?ref=badge" target="_blank" rel="noopener noreferrer">
        <span class="prefix">Built with</span>
        <span class="brand">
          <svg width="14" height="14" viewBox="0 0 36 36" fill="none">
            <path d="M4 32 L18 4 L32 32 Z" fill="#E5E5E5"/>
            <path d="M18 4 L4 32 L18 32 Z" fill="#B3B3B3"/>
            <path d="M18 4 L18 32 L32 4 Z" fill="#808080"/>
          </svg>
          ProductOS
        </span>
      </a>
      <button class="close" onclick="this.parentElement.remove();try{localStorage.setItem('productos-badge-dismissed','true');}catch(e){}" aria-label="Close">&times;</button>
    `;
    document.body.appendChild(badge);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', inject);
  } else {
    inject();
  }
})();