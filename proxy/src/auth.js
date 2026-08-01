// Verificación de los dos JWT de Firebase contra sus JWKS públicos, con
// WebCrypto. No se usa el Admin SDK de Firebase: no corre en Workers (necesita
// Node) y para esto alcanzan 80 líneas.
//
//   App Check  -> prueba que la llamada viene de la app real instalada de Play.
//   ID token   -> prueba que hay un usuario logueado, y da su uid.
//
// Hacen falta LOS DOS. Solo App Check permitiría que alguien que decompiló el
// APK use el lector sin cuenta; solo Auth permitiría pegarle con curl.

import { ERRORES } from './contrato.js';

const JWKS_APPCHECK = 'https://firebaseappcheck.googleapis.com/v1/jwks';
const JWKS_AUTH =
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';

// Cache a nivel de módulo: sobrevive entre requests mientras el isolate viva.
// crypto.subtle.importKey es lo caro en CPU y el plan gratis da 10 ms por
// invocación, así que importar las claves en cada ticket no entraría.
const cacheJwks = new Map(); // url -> { claves: Map<kid, CryptoKey>, expira }

async function clavePara(url, kid) {
  const entrada = cacheJwks.get(url);
  if (entrada && entrada.expira > Date.now()) {
    const k = entrada.claves.get(kid);
    if (k) return k;
  }
  // cacheEverything hace que el fetch salga del caché de Cloudflare casi siempre:
  // no suma latencia ni cuenta como CPU.
  const res = await fetch(url, { cf: { cacheTtl: 3600, cacheEverything: true } });
  if (!res.ok) throw ERRORES.interno();
  const cuerpo = await res.json();
  const claves = new Map();
  for (const jwk of cuerpo.keys ?? []) {
    if (jwk.kty !== 'RSA' || !jwk.kid) continue;
    claves.set(
      jwk.kid,
      await crypto.subtle.importKey(
        'jwk',
        { kty: 'RSA', n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        false,
        ['verify']
      )
    );
  }
  cacheJwks.set(url, { claves, expira: Date.now() + 60 * 60 * 1000 });
  return claves.get(kid) ?? null;
}

function bytesB64Url(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function textoB64Url(s) {
  return new TextDecoder().decode(bytesB64Url(s));
}

async function verificarJwt(token, jwksUrl, { iss, auds }, error) {
  if (!token || typeof token !== 'string') throw error();
  const partes = token.split('.');
  if (partes.length !== 3) throw error();

  let cabecera;
  try {
    cabecera = JSON.parse(textoB64Url(partes[0]));
  } catch {
    throw error();
  }
  if (cabecera.alg !== 'RS256' || !cabecera.kid) throw error();

  const clave = await clavePara(jwksUrl, cabecera.kid);
  if (!clave) throw error();

  const firmaOk = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    clave,
    bytesB64Url(partes[2]),
    new TextEncoder().encode(`${partes[0]}.${partes[1]}`)
  );
  if (!firmaOk) throw error();

  let datos;
  try {
    datos = JSON.parse(textoB64Url(partes[1]));
  } catch {
    throw error();
  }

  const ahora = Math.floor(Date.now() / 1000);
  const margen = 60; // relojes desincronizados en celulares viejos
  if (!datos.exp || datos.exp + margen < ahora) throw error();
  if (datos.iat && datos.iat - margen > ahora) throw error();
  if (datos.iss !== iss) throw error();

  const audiencias = Array.isArray(datos.aud) ? datos.aud : [datos.aud];
  if (!auds.some((a) => audiencias.includes(a))) throw error();

  return datos;
}

export function verificarAppCheck(token, env) {
  return verificarJwt(
    token,
    JWKS_APPCHECK,
    {
      iss: `https://firebaseappcheck.googleapis.com/${env.FIREBASE_PROJECT_NUMBER}`,
      auds: [
        `projects/${env.FIREBASE_PROJECT_NUMBER}`,
        `projects/${env.FIREBASE_PROJECT_ID}`,
      ],
    },
    ERRORES.appNoValidada
  );
}

export async function verificarUsuario(token, env) {
  const datos = await verificarJwt(
    token,
    JWKS_AUTH,
    {
      iss: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
      auds: [env.FIREBASE_PROJECT_ID],
    },
    ERRORES.sesionVencida
  );
  if (!datos.sub) throw ERRORES.sesionVencida();
  return datos.sub; // uid
}
