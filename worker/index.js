// QR-save: servicio de redirección para QR dinámicos (Cloudflare Workers + KV).
// Enlace de binding KV requerido: variable "QR".

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
      if (!body.secret || body.secret !== rec.secret) return json({ error: "sin permiso" }, 403);
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
