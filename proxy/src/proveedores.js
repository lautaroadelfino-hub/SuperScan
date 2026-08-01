// Adaptadores de proveedor. Todos exponen la MISMA forma, así que agregar uno
// nuevo es agregar una entrada en este objeto y su id en la variable CADENA.
// La app no sabe cuál se usó ni le importa.
//
// Detalle de implementación importante: el cuerpo se arma como
//   prefijo (texto) + base64 de la foto (bytes, tal cual llegaron) + sufijo (texto)
// dentro de un Blob. Así el base64 NUNCA se decodifica a string de JS ni pasa
// por JSON.stringify — que sobre 700 KB es justo lo que hace explotar el límite
// de 10 ms de CPU del plan gratuito de Workers.

import { SISTEMA, PROMPT } from './contrato.js';

const S = JSON.stringify(SISTEMA);
const P = JSON.stringify(PROMPT);

// Formato OpenAI (Groq, Mistral y casi cualquier proveedor nuevo). Que la mitad
// del mercado hable este dialecto es la razón principal para que el proxy exista:
// cambiar de proveedor suele ser cambiar una URL y un nombre de modelo.
function estiloOpenAI({ url, modelo, apiKey, mime, jsonMode = true }) {
  const rf = jsonMode ? '"response_format":{"type":"json_object"},' : '';
  return {
    url,
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${apiKey}`,
    },
    prefijo:
      `{"model":${JSON.stringify(modelo)},"temperature":0,"max_tokens":4096,${rf}` +
      `"messages":[{"role":"system","content":${S}},` +
      `{"role":"user","content":[{"type":"text","text":${P}},` +
      `{"type":"image_url","image_url":{"url":"data:${mime};base64,`,
    sufijo: `"}}]}]}`,
    extraer: (j) => j?.choices?.[0]?.message?.content ?? null,
  };
}

export const PROVEEDORES = {
  // --- Cloudflare Workers AI --------------------------------------------
  // 10.000 neurons/día que se resetean solos, sin tarjeta. Si se acaban, corta;
  // no cobra. El token solo necesita el permiso "Workers AI: Read".
  //
  // Alternativa sin ninguna credencial: descomentar el binding [ai] en
  // wrangler.toml y llamar env.AI.run(). Queda más lindo para el requisito de
  // "cero secretos", pero obliga a construir un objeto JS con el base64 adentro
  // y a que el runtime lo serialice: más CPU, que es el recurso escaso acá.
  'workers-ai': {
    secreto: 'CF_AI_TOKEN',
    modeloVar: 'MODELO_WORKERS_AI',
    arma(env, mime, modelo) {
      return {
        url: `https://api.cloudflare.com/client/v4/accounts/${env.CF_ACCOUNT_ID}/ai/run/${modelo}`,
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${env.CF_AI_TOKEN}`,
        },
        prefijo:
          `{"max_tokens":4096,"temperature":0,` +
          // Gemma 4 tiene "thinking mode" y los tokens de razonamiento se
          // facturan como salida, que es el rubro caro. Para extraer un JSON
          // no aporta nada: apagado.
          `"reasoning_effort":"none",` +
          `"messages":[{"role":"system","content":${S}},` +
          `{"role":"user","content":[{"type":"text","text":${P}},` +
          `{"type":"image_url","image_url":{"url":"data:${mime};base64,`,
        sufijo: `"}}]}]}`,
        extraer: (j) => {
          const r = j?.result?.response;
          if (typeof r === 'string') return r;
          if (r && typeof r === 'object') return JSON.stringify(r);
          return j?.result?.choices?.[0]?.message?.content ?? null;
        },
      };
    },
  },

  // --- Groq --------------------------------------------------------------
  // Gratis de verdad y rapidísimo, pero el modelo con visión está marcado
  // "Preview": Groq avisa por escrito que lo puede apagar sin preaviso. Por eso
  // va como segundo de la cadena, no como único.
  groq: {
    secreto: 'GROQ_API_KEY',
    modeloVar: 'MODELO_GROQ',
    arma(env, mime, modelo) {
      return estiloOpenAI({
        url: 'https://api.groq.com/openai/v1/chat/completions',
        modelo,
        apiKey: env.GROQ_API_KEY,
        mime,
      });
    },
  },

  // --- Mistral -----------------------------------------------------------
  // OJO: en el plan gratuito Mistral entrena con los datos salvo que apagues
  // el toggle en Admin Console -> Privacy. Si se activa este proveedor hay que
  // apagarlo ANTES del primer ticket de un usuario real.
  mistral: {
    secreto: 'MISTRAL_API_KEY',
    modeloVar: 'MODELO_MISTRAL',
    arma(env, mime, modelo) {
      return estiloOpenAI({
        url: 'https://api.mistral.ai/v1/chat/completions',
        modelo,
        apiKey: env.MISTRAL_API_KEY,
        mime,
      });
    },
  },

  // --- Gemini por API key (Google AI / Generative Language) ---------------
  // Es el que mejor lee tickets de los cuatro. Está acá para poder volver a
  // Gemini sin publicar una versión: hoy la app le pega directo con Firebase AI
  // Logic. Ojo con el tier gratuito de esta API: Google lo usa para mejorar sus
  // productos. Si se activa, revisar la política de privacidad publicada.
  'google-ai': {
    secreto: 'GOOGLE_AI_API_KEY',
    modeloVar: 'MODELO_GOOGLE_AI',
    arma(env, mime, modelo) {
      return {
        url: `https://generativelanguage.googleapis.com/v1beta/models/${modelo}:generateContent`,
        headers: {
          'content-type': 'application/json',
          'x-goog-api-key': env.GOOGLE_AI_API_KEY,
        },
        prefijo:
          `{"generationConfig":{"temperature":0,"responseMimeType":"application/json"},` +
          `"systemInstruction":{"parts":[{"text":${S}}]},` +
          `"contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"${mime}","data":"`,
        sufijo: `"}},{"text":${P}}]}]}`,
        extraer: (j) =>
          j?.candidates?.[0]?.content?.parts?.map((p) => p?.text ?? '').join('') || null,
      };
    },
  },
};

/**
 * Lista de proveedores a usar, en orden, salteando los que no tengan su secreto
 * cargado. Que se salteen solos es lo que permite deployar el Worker antes de
 * tener todas las cuentas creadas.
 */
export function cadenaActiva(env) {
  const ids = String(env.CADENA ?? 'workers-ai')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const salida = [];
  for (const id of ids) {
    const p = PROVEEDORES[id];
    if (!p) continue;
    if (p.secreto && !env[p.secreto]) continue;
    if (id === 'workers-ai' && !env.CF_ACCOUNT_ID) continue;
    salida.push({ id, def: p, modelo: env[p.modeloVar] });
  }
  return salida;
}

/** ¿Vale la pena pasar al siguiente proveedor, o el error es nuestro? */
export function esFalloDeProveedor(estado) {
  // 429 = sin cuota / rate limit. 5xx = el proveedor se cayó.
  // 404 = deprecaron el modelo (pasa seguido en los tiers gratis).
  // 401/403 = la key está mal: no lo arregla reintentar, pero probar el
  // siguiente proveedor sí salva el escaneo del usuario.
  return estado === 429 || estado === 404 || estado === 401 || estado === 403 || estado >= 500;
}
