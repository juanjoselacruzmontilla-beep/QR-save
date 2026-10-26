# QR-save

App Android para crear y editar QR dinámicos sin vencimiento y sin costo.

## Cómo funciona
- El QR dinámico NO contiene tu URL: contiene `https://TU-SERVIDOR/r/12345678`.
- El servidor (carpeta `worker/`, gratis en Cloudflare) guarda a dónde apunta cada número y redirige.
- Al editar, la app solo cambia el destino en el servidor; el QR impreso nunca cambia.
- Cada QR dinámico tiene un número de 8 dígitos (también se imprime debajo del QR).
- Solo el teléfono que creó el QR puede editarlo (guarda una clave secreta local).

## Paso A — Servidor gratis (Cloudflare, ~5 min, sin tarjeta)
1. Crea cuenta en https://dash.cloudflare.com
2. Workers & Pages → Create → Create Worker → nombre `qr-save` → Deploy.
3. Edit code → borra todo, pega el contenido de `worker/index.js` → Deploy.
4. Storage & Databases → KV → Create namespace → nombre `qr-save-kv`.
5. Vuelve al Worker → Settings → Bindings → Add → KV namespace → Variable name: `QR` → elige `qr-save-kv` → Deploy.
6. Tu dirección queda como `https://qr-save.TUUSUARIO.workers.dev` (se ve en el panel del Worker).
   Prueba abrirla en el navegador: debe decir "QR-save activo".

## Paso B — Compilar el APK gratis (GitHub, sin instalar nada)
1. Crea cuenta en https://github.com y un repositorio PÚBLICO nuevo (Actions es gratis en repos públicos).
2. Sube TODO el contenido de esta carpeta (incluida la carpeta oculta `.github`).
3. Pestaña Actions → "Build APK" → espera ~5 min → entra a la ejecución → descarga `QR-save-APK` (zip con `app-debug.apk`).
4. Pasa el APK al teléfono e instálalo (permite "instalar apps desconocidas").

## Primer uso
Al pulsar "Generar QR Dinámico" o "Escanear y editar" la app pide la dirección del servidor (Paso A.6). Se guarda una sola vez.
(Para cambiarla después: mantén pulsado el título "QR-save".)
