// Proxy de lectura de tickets de Góndola.
//
// Qué resuelve:
//  1. La API key del proveedor de IA vive acá (cifrada en Cloudflare), no en el APK.
//  2. Solo entra la app real (App Check) con un usuario logueado (Firebase Auth).
//  3. La app no sabe qué proveedor hay del otro lado: cambiarlo no necesita
//     una versión nueva en Play.
//  4. Cuando todo falla, devuelve un error con un mensaje escrito para el
//     usuario, no un stack trace.
//
// Regla que no se rompe: NUNCA se loguea el cuerpo del pedido ni el texto que
// devuelve el modelo. Un ticket dice dónde compra una persona, cuándo y qué.

import {
  VERSION_CONTRATO,
  ERRORES,
  ErrorProxy,
  respuestaError,
  json,
  extraerJson,
  normalizarTicket,
} from './contrato.js';
import { verificarAppCheck, verificarUsuario } from './auth.js';
import { cadenaActiva, esFalloDeProveedor } from './proveedores.js';

const MIMES_OK = new Set(['image/jpeg', 'image/png', 'image/webp']);

export default {
  async fetch(request, env, ctx) {
    try {
      return await manejar(request, env, ctx);
    } catch (e) {
      if (!(e instanceof ErrorProxy)) console.error('inesperado', e?.stack ?? String(e));
      return respuestaError(e);
    }
  },
};

async function manejar(request, env, ctx) {
  const url = new URL(request.url);

  // Sonda para monitoreo externo (UptimeRobot y similares, gratis). No toca
  // ningún proveedor ni gasta cuota.
  if (request.method === 'GET' && url.pathname === '/salud') {
    return json({
      ok: true,
      contrato: VERSION_CONTRATO,
      habilitado: env.HABILITADO !== 'no',
      proveedores: cadenaActiva(env).map((p) => p.id),
    });
  }

  if (url.pathname !== '/v1/ticket') return json({ ok: false }, 404);
  if (request.method !== 'POST') return json({ ok: false }, 405);

  if (env.HABILITADO === 'no') throw ERRORES.mantenimiento(env.MENSAJE_MANTENIMIENTO);

  // --- 1. Autenticación: los dos tokens, siempre ---
  const tokenAppCheck = request.headers.get('x-firebase-appcheck');
  const auth = request.headers.get('authorization') ?? '';
  const tokenUsuario = auth.startsWith('Bearer ') ? auth.slice(7) : '';

  const [, uid] = await Promise.all([
    verificarAppCheck(tokenAppCheck, env),
    verificarUsuario(tokenUsuario, env),
  ]);

  // --- 2. Cuerpo: base64 crudo, sin JSON alrededor ---
  const mime = (request.headers.get('x-mime') ?? 'image/jpeg').toLowerCase();
  if (!MIMES_OK.has(mime)) throw ERRORES.pedidoInvalido('Formato de imagen no soportado.');

  const maximo = Number(env.MAX_BASE64_BYTES ?? 1600000);
  const declarado = Number(request.headers.get('content-length') ?? 0);
  if (declarado > maximo) throw ERRORES.fotoMuyGrande();

  const imagenB64 = new Uint8Array(await request.arrayBuffer());
  if (imagenB64.byteLength < 1000) throw ERRORES.pedidoInvalido('La foto llegó vacía.');
  if (imagenB64.byteLength > maximo) throw ERRORES.fotoMuyGrande();

  // --- 3. Frenos de cuota (el cupo gratis es uno solo para todos) ---
  const hoy = new Date().toISOString().slice(0, 10);
  await verificarCuotas(env, uid, hoy);

  // --- 4. Cascada de proveedores ---
  const cadena = cadenaActiva(env);
  if (cadena.length === 0) throw ERRORES.mantenimiento();

  const arranque = Date.now();
  let ultimoFallo = null;

  for (const proveedor of cadena) {
    const t0 = Date.now();
    try {
      const texto = await pedir(proveedor, env, mime, imagenB64);
      const { ticket, aviso } = normalizarTicket(extraerJson(texto));

      ctx.waitUntil(sumarCuotas(env, uid, hoy));
      // Telemetría mínima: sirve para saber qué proveedor está andando y cuánto
      // tarda. Ni uid completo, ni imagen, ni texto del modelo.
      console.log(
        JSON.stringify({
          evt: 'ok',
          prov: proveedor.id,
          ms: Date.now() - arranque,
          items: ticket.items.length,
          conf: ticket.confianza,
        })
      );

      return json({
        ok: true,
        contrato: VERSION_CONTRATO,
        ticket,
        aviso,
        meta: { proveedor: proveedor.id, modelo: proveedor.modelo, ms: Date.now() - arranque },
      });
    } catch (e) {
      // NO_LEGIBLE también hace saltar al siguiente: puede ser que ESTE modelo
      // no sepa leer este ticket y el que sigue sí.
      ultimoFallo = e;
      console.log(
        JSON.stringify({
          evt: 'fallo',
          prov: proveedor.id,
          ms: Date.now() - t0,
          causa: e instanceof ErrorProxy ? e.codigo : (e?.name ?? 'error'),
        })
      );
    }
  }

  // Se acabaron los proveedores. Se prioriza el mensaje más informativo.
  if (ultimoFallo instanceof ErrorProxy && ultimoFallo.codigo === 'NO_LEGIBLE') throw ultimoFallo;
  if (ultimoFallo instanceof ErrorProxy && ultimoFallo.codigo === 'SIN_CUOTA') throw ultimoFallo;
  throw ERRORES.proveedorCaido();
}

/** Una llamada a un proveedor. Devuelve el texto crudo del modelo. */
async function pedir(proveedor, env, mime, imagenB64) {
  const { url, headers, prefijo, sufijo, extraer } = proveedor.def.arma(env, mime, proveedor.modelo);
  const enc = new TextEncoder();

  // El Blob concatena sin decodificar el base64: memcpy, no parseo. Además le
  // da Content-Length al pedido, así que no sale con transfer-encoding chunked
  // (hay APIs que lo rechazan).
  const cuerpo = new Blob([enc.encode(prefijo), imagenB64, enc.encode(sufijo)], {
    type: 'application/json',
  });

  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers,
      body: cuerpo,
      signal: AbortSignal.timeout(Number(env.TIMEOUT_MS ?? 45000)),
    });
  } catch {
    throw ERRORES.proveedorCaido(); // timeout o el proveedor no atendió
  }

  if (!res.ok) {
    // El texto del error se lee para el log, pero no viaja al usuario.
    const detalle = (await res.text().catch(() => '')).slice(0, 200);
    console.log(JSON.stringify({ evt: 'http', prov: proveedor.id, st: res.status, detalle }));
    if (res.status === 429) throw ERRORES.sinCuota();
    if (esFalloDeProveedor(res.status)) throw ERRORES.proveedorCaido();
    throw ERRORES.interno();
  }

  const texto = extraer(await res.json());
  if (!texto) throw ERRORES.noLegible();
  return texto;
}

// ---------------------------------------------------------------------------
// Cuotas. KV es eventualmente consistente, así que el conteo es aproximado:
// alcanza para que nadie vacíe el cupo diario, no para facturar.
// Si el binding CUOTAS no existe, el proxy anda igual y sin límites.
// ---------------------------------------------------------------------------

async function verificarCuotas(env, uid, hoy) {
  if (!env.CUOTAS) return;
  const [usuario, global] = await Promise.all([
    env.CUOTAS.get(`u:${uid}:${hoy}`),
    env.CUOTAS.get(`g:${hoy}`),
  ]);
  if (Number(usuario ?? 0) >= Number(env.LIMITE_DIARIO_USUARIO ?? 25)) throw ERRORES.limiteUsuario();
  if (Number(global ?? 0) >= Number(env.LIMITE_DIARIO_GLOBAL ?? 400)) throw ERRORES.sinCuota();
}

async function sumarCuotas(env, uid, hoy) {
  if (!env.CUOTAS) return;
  const ttl = 172800; // dos días: sobra para cubrir el cambio de fecha UTC
  const clavesYValores = [
    [`u:${uid}:${hoy}`, Number(await env.CUOTAS.get(`u:${uid}:${hoy}`)) || 0],
    [`g:${hoy}`, Number(await env.CUOTAS.get(`g:${hoy}`)) || 0],
  ];
  await Promise.all(
    clavesYValores.map(([k, v]) =>
      env.CUOTAS.put(k, String(v + 1), { expirationTtl: ttl }).catch(() => {})
    )
  );
}
