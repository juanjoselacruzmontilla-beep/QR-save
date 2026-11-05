// QR-save: servicio de redirección para QR dinámicos (Cloudflare Workers + KV).
// Binding KV requerido: variable "QR".
// Opcional: secreto "ADMIN_KEY" (clave maestra para editar cualquier QR desde cualquier teléfono).

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function isHttpUrl(s) {
  try {
    const u = new URL(s);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch (e) {
    return false;
  }
}


function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// Apps conocidas: al escanear, se intenta abrir la app y no el navegador.
const GMAPS = "com.google.android.apps.maps";
const APPS = [
  { name: "Instagram", pkg: "com.instagram.android", hosts: ["instagram.com", "www.instagram.com", "instagr.am"] },
  { name: "Facebook", pkg: "com.facebook.katana", hosts: ["facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.com", "fb.me"] },
  { name: "WhatsApp", pkg: "com.whatsapp", hosts: ["wa.me", "api.whatsapp.com", "chat.whatsapp.com", "whatsapp.com", "www.whatsapp.com"] },
  { name: "TikTok", pkg: "com.zhiliaoapp.musically", hosts: ["tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com"] },
  { name: "YouTube", pkg: "com.google.android.youtube", hosts: ["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"] },
  { name: "X", pkg: "com.twitter.android", hosts: ["x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"] },
  { name: "Telegram", pkg: "org.telegram.messenger", hosts: ["t.me", "telegram.me"] },
  { name: "LinkedIn", pkg: "com.linkedin.android", hosts: ["linkedin.com", "www.linkedin.com", "lnkd.in"] },
  { name: "Spotify", pkg: "com.spotify.music", hosts: ["open.spotify.com", "spotify.link"] },
  { name: "Pinterest", pkg: "com.pinterest", hosts: ["pinterest.com", "www.pinterest.com", "pin.it"] },
  { name: "Snapchat", pkg: "com.snapchat.android", hosts: ["snapchat.com", "www.snapchat.com"] },
  { name: "Google Maps", pkg: GMAPS, hosts: ["maps.app.goo.gl", "g.page", "maps.google.com", "search.google.com"] },
];

function findApp(u) {
  const host = u.hostname.toLowerCase();
  if ((host === "www.google.com" || host === "google.com") && u.pathname.startsWith("/maps")) {
    return APPS[APPS.length - 1];
  }
  return APPS.find((a) => a.hosts.includes(host)) || null;
}

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function androidIntentUrl(u, pkg) {
  const noHash = new URL(u.toString());
  noHash.hash = "";
  const rest = noHash.host + noHash.pathname + noHash.search;
  return "intent://" + rest + "#Intent;scheme=" + noHash.protocol.replace(":", "") +
    ";package=" + pkg + ";S.browser_fallback_url=" + encodeURIComponent(noHash.toString()) + ";end";
}

function openPage(appName, target, intentUrl) {
  const primary = intentUrl || target;
  const html = `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Abriendo ${esc(appName)}</title>
<style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;background:#0F172A;color:#fff;font-family:system-ui,sans-serif;text-align:center;padding:24px}
a.b{display:block;background:#F59E0B;color:#0F172A;font-weight:700;font-size:20px;padding:18px 28px;border-radius:14px;text-decoration:none}
a.s{color:#94a3b8}</style></head><body>
<div style="font-size:22px">Abriendo ${esc(appName)}…</div>
<a class="b" href="${esc(primary)}">Abrir en ${esc(appName)}</a>
<a class="s" href="${esc(target)}">Abrir en el navegador</a>
${intentUrl ? `<script>location.href=${JSON.stringify(intentUrl).replace(/</g, "\\u003c")};</script>` : ""}
</body></html>`;
  return new Response(html, {
    status: 200,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}

function randomId() {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  return String(10000000 + (buf[0] % 90000000)); // 8 dígitos
}

function randomSecret() {
  const buf = new Uint8Array(16);
  crypto.getRandomValues(buf);
  return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    // Redirección pública: /r/12345678
    if (request.method === "GET" && parts[0] === "r" && parts[1]) {
      const raw = await env.QR.get("q:" + parts[1]);
      if (!raw) return new Response("QR no encontrado", { status: 404 });
      const rec = JSON.parse(raw);
      if (!rec.url) {
        return new Response("Este QR todavía no tiene destino asignado.", {
          status: 200,
          headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
        });
      }
      const ua = request.headers.get("user-agent") || "";
      const isAndroid = /Android/i.test(ua);
      const isIOS = /iPhone|iPad|iPod/i.test(ua);
      let target = null;
      try { target = new URL(rec.url); } catch (e) {}
      const app = target ? findApp(target) : null;
      if (app && isAndroid) return openPage(app.name, rec.url, androidIntentUrl(target, app.pkg));
      if (app && isIOS) return openPage(app.name, rec.url, null);
      return new Response(null, {
        status: 302,
        headers: { location: rec.url, "cache-control": "no-store" },
      });
    }

    // Crear: POST /api/create  {url?}
    if (request.method === "POST" && url.pathname === "/api/create") {
      let body = {};
      try { body = await request.json(); } catch (e) {}
      const target = (body.url || "").trim();
      if (target && !isHttpUrl(target)) return json({ error: "URL inválida" }, 400);
      let id = null;
      for (let i = 0; i < 6; i++) {
        const c = randomId();
        if (!(await env.QR.get("q:" + c))) { id = c; break; }
      }
      if (!id) return json({ error: "No se pudo asignar número" }, 500);
      const secret = randomSecret();
      await env.QR.put("q:" + id, JSON.stringify({ url: target, secret }));
      return json({ id, secret });
    }

    // Leer destino: GET /api/get/12345678
    if (request.method === "GET" && parts[0] === "api" && parts[1] === "get" && parts[2]) {
      const raw = await env.QR.get("q:" + parts[2]);
      if (!raw) return json({ error: "no existe" }, 404);
      return json({ url: JSON.parse(raw).url || "" });
    }

    // Editar: PUT /api/update/12345678  {secret, url}
    if (request.method === "PUT" && parts[0] === "api" && parts[1] === "update" && parts[2]) {
      let body = {};
      try { body = await request.json(); } catch (e) {}
      const raw = await env.QR.get("q:" + parts[2]);
      if (!raw) return json({ error: "no existe" }, 404);
      const rec = JSON.parse(raw);
      const okOwn = safeEqual(body.secret, rec.secret);
      const okAdmin = !!env.ADMIN_KEY && safeEqual(body.secret, env.ADMIN_KEY);
      if (!okOwn && !okAdmin) return json({ error: "sin permiso" }, 403);
      const target = (body.url || "").trim();
      if (target && !isHttpUrl(target)) return json({ error: "URL inválida" }, 400);
      rec.url = target;
      await env.QR.put("q:" + parts[2], JSON.stringify(rec));
      return json({ ok: true });
    }

    if (url.pathname === "/") {
      return new Response("QR-save activo", { headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    return new Response("No encontrado", { status: 404 });
  },
};
