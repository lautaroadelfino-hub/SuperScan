// Contrato entre la app y el proxy: prompt, categorías, errores y normalización
// del ticket. Todo esto vive del lado del servidor A PROPÓSITO: mejorar el
// prompt o el texto de un error no puede depender de que Play apruebe una
// versión nueva.

export const VERSION_CONTRATO = 1;

// Ojo: tiene que coincidir con ReceiptScannerService.PRESET_CATEGORIES de la
// app, que es lo que muestra el desplegable de la pantalla de confirmación.
// Si acá se agrega una categoría nueva, la app la muestra igual (es un String),
// pero el usuario no la va a poder elegir a mano hasta la próxima versión.
export const CATEGORIAS = [
  'Lácteos y Quesos',
  'Bebidas',
  'Limpieza y Hogar',
  'Panadería y Dulces',
  'Frutas y Verduras',
  'Carnicería y Pescadería',
  'Almacén y Comestibles',
  'Cuidado Personal',
  'Mascotas',
  'Otros',
];

export const SISTEMA =
  'Sos un extractor de datos de tickets de supermercado argentinos. Respondés ' +
  'SIEMPRE con un único objeto JSON válido, sin texto antes ni después y sin ' +
  'bloques de markdown. Nunca inventás datos: si un campo no se lee, lo dejás ' +
  'en 0 o en cadena vacía y bajás la confianza.';

export const PROMPT = `Extraé la información de este ticket de supermercado argentino (Vea, Carrefour, Día, Cooperativa Obrera u otro).

Devolvé ÚNICAMENTE un objeto JSON con esta estructura exacta:
{
  "comercio": "nombre del supermercado tal como figura en el ticket",
  "fecha": "YYYY-MM-DD",
  "total": 0.0,
  "confianza": 0.0,
  "items": [
    { "descripcion": "producto", "category": "categoría", "cantidad": 1, "precioUnitario": 0.0, "precioTotal": 0.0 }
  ]
}

Reglas:
- Los precios están en pesos argentinos con COMA decimal y punto de miles ("1.249,50" son mil doscientos cuarenta y nueve con cincuenta). Devolvelos como número JSON con punto decimal: 1249.5
- La fecha del ticket suele venir dd/mm/aaaa. Convertila a YYYY-MM-DD.
- "cantidad" puede ser decimal en lo que se vende por peso (0.482 kg).
- Si una línea muestra solo el precio total, calculá el unitario dividiendo por la cantidad.
- NO incluyas como ítems las líneas de descuento, promoción, subtotal, redondeo, medios de pago ni puntos.
- Copiá la descripción tal cual está impresa, aunque esté abreviada. No la "corrijas" ni la completes.
- Si un número no se lee con seguridad, es preferible poner 0 antes que adivinar.
- "confianza" es de 0 a 1 y refleja qué tan legible estaba el ticket.
- "category" tiene que ser EXACTAMENTE una de estas: ${CATEGORIAS.map((c) => `"${c}"`).join(', ')}. Si ninguna aplica, "Otros".`;

// ---------------------------------------------------------------------------
// Errores
// ---------------------------------------------------------------------------

// El mensaje de usuario se arma acá y viaja en la respuesta: así la app muestra
// algo útil sin tener que conocer a ningún proveedor, y el texto se puede
// mejorar con un deploy del Worker.
export class ErrorProxy extends Error {
  constructor(codigo, mensaje, opciones = {}) {
    super(`${codigo}: ${mensaje}`);
    this.codigo = codigo;
    this.mensajeUsuario = mensaje;
    this.estado = opciones.estado ?? 500;
    this.reintentable = opciones.reintentable ?? false;
    this.reintentarEnSegundos = opciones.reintentarEnSegundos ?? 0;
  }
}

export const ERRORES = {
  appNoValidada: () =>
    new ErrorProxy(
      'APP_NO_VALIDADA',
      'No pudimos validar la app. Si la instalaste por fuera de Google Play, probá con la versión de la tienda.',
      { estado: 401 }
    ),
  sesionVencida: () =>
    new ErrorProxy(
      'SESION_VENCIDA',
      'Tu sesión venció. Cerrá sesión y volvé a entrar.',
      { estado: 401 }
    ),
  pedidoInvalido: (detalle) =>
    new ErrorProxy('PEDIDO_INVALIDO', detalle, { estado: 400 }),
  fotoMuyGrande: () =>
    new ErrorProxy(
      'FOTO_MUY_GRANDE',
      'La foto pesa demasiado. Sacala de nuevo con menos zoom.',
      { estado: 413 }
    ),
  limiteUsuario: () =>
    new ErrorProxy(
      'LIMITE_DIARIO_USUARIO',
      'Llegaste al máximo de tickets por hoy. Mañana podés seguir.',
      { estado: 429, reintentable: false }
    ),
  sinCuota: () =>
    new ErrorProxy(
      'SIN_CUOTA',
      'El lector de tickets se quedó sin cupo por hoy. No es culpa tuya: mañana vuelve solo. Podés cargar el ticket a mano mientras tanto.',
      { estado: 429, reintentable: false }
    ),
  mantenimiento: (mensaje) =>
    new ErrorProxy(
      'MANTENIMIENTO',
      mensaje || 'El lector de tickets está en mantenimiento. Volvé a probar más tarde.',
      { estado: 503 }
    ),
  proveedorCaido: () =>
    new ErrorProxy(
      'PROVEEDOR_CAIDO',
      'El lector de tickets no está respondiendo. Probá de nuevo en unos minutos.',
      { estado: 503, reintentable: true, reintentarEnSegundos: 60 }
    ),
  noLegible: () =>
    new ErrorProxy(
      'NO_LEGIBLE',
      'No pudimos leer este ticket. Probá con más luz, sin sombras y con el ticket estirado.',
      { estado: 422, reintentable: true }
    ),
  interno: () =>
    new ErrorProxy(
      'ERROR_INTERNO',
      'Algo se rompió de nuestro lado. Probá de nuevo en un rato.',
      { estado: 500, reintentable: true, reintentarEnSegundos: 30 }
    ),
};

export function respuestaError(err) {
  const e = err instanceof ErrorProxy ? err : ERRORES.interno();
  // El cuerpo del error NUNCA lleva el mensaje crudo del proveedor: puede
  // filtrar nombres de modelo, límites o pedazos del prompt.
  return json(
    {
      ok: false,
      contrato: VERSION_CONTRATO,
      error: {
        codigo: e.codigo,
        mensaje: e.mensajeUsuario,
        reintentable: e.reintentable,
        reintentarEnSegundos: e.reintentarEnSegundos,
      },
    },
    e.estado
  );
}

export function json(cuerpo, estado = 200) {
  return new Response(JSON.stringify(cuerpo), {
    status: estado,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

// ---------------------------------------------------------------------------
// Normalización del ticket
// ---------------------------------------------------------------------------

// Los modelos devuelven números como "1.249,50", "$1249.50" o directamente
// number. Acá se unifica al formato argentino: coma decimal, punto de miles.
export function aNumero(v) {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v !== 'string') return 0;
  let s = v.replace(/[^\d.,-]/g, '').trim();
  if (!s) return 0;
  const coma = s.lastIndexOf(',');
  const punto = s.lastIndexOf('.');
  if (coma > punto) s = s.replace(/\./g, '').replace(',', '.');
  else if (punto > coma) s = s.replace(/,/g, '');
  const n = parseFloat(s);
  return Number.isFinite(n) ? n : 0;
}

function aFecha(v) {
  if (typeof v !== 'string') return '';
  const s = v.trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
  const m = s.match(/^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})$/);
  if (!m) return '';
  const [, d, mes, a] = m;
  const anio = a.length === 2 ? `20${a}` : a;
  return `${anio}-${mes.padStart(2, '0')}-${d.padStart(2, '0')}`;
}

/**
 * Valida y arregla lo que devolvió el modelo. Además hace el chequeo aritmético,
 * que es el detector barato de alucinación: si la suma de los ítems no da el
 * total impreso, el modelo se comió o inventó un número. No se descarta el
 * ticket (a veces el ticket trae descuentos), se baja la confianza y se avisa.
 */
export function normalizarTicket(bruto) {
  if (!bruto || typeof bruto !== 'object') throw ERRORES.noLegible();

  const items = Array.isArray(bruto.items) ? bruto.items : [];
  const limpios = [];
  for (const it of items.slice(0, 200)) {
    if (!it || typeof it !== 'object') continue;
    const descripcion = String(it.descripcion ?? it.description ?? '').trim().slice(0, 120);
    if (!descripcion) continue;

    let cantidad = aNumero(it.cantidad);
    let unitario = aNumero(it.precioUnitario);
    let total = aNumero(it.precioTotal);
    if (cantidad <= 0) cantidad = 1;
    if (total <= 0 && unitario > 0) total = redondear(unitario * cantidad);
    if (unitario <= 0 && total > 0) unitario = redondear(total / cantidad);
    if (total <= 0 && unitario <= 0) continue; // línea sin ningún precio: ruido

    const categoria = CATEGORIAS.includes(it.category) ? it.category : 'Otros';
    limpios.push({
      descripcion,
      category: categoria,
      cantidad,
      precioUnitario: unitario,
      precioTotal: total,
    });
  }

  if (limpios.length === 0) throw ERRORES.noLegible();

  const total = aNumero(bruto.total);
  const suma = redondear(limpios.reduce((a, i) => a + i.precioTotal, 0));
  let confianza = aNumero(bruto.confianza);
  if (confianza <= 0 || confianza > 1) confianza = 0.8;

  let aviso = '';
  const tolerancia = Math.max(total * 0.02, 1);
  if (total > 0 && Math.abs(suma - total) > tolerancia) {
    confianza = Math.min(confianza, 0.5);
    aviso =
      `La suma de los productos (${formatoPesos(suma)}) no coincide con el total del ticket ` +
      `(${formatoPesos(total)}). Revisá los precios antes de guardar.`;
  }

  return {
    ticket: {
      comercio: String(bruto.comercio ?? '').trim().slice(0, 80) || 'Desconocido',
      fecha: aFecha(bruto.fecha),
      total: total > 0 ? total : suma,
      confianza,
      items: limpios,
    },
    aviso,
  };
}

function redondear(n) {
  return Math.round(n * 100) / 100;
}

function formatoPesos(n) {
  return '$' + n.toFixed(2).replace('.', ',');
}

/**
 * El modelo a veces envuelve el JSON en ```json … ``` o le agrega una frase.
 * Se limpia acá y no en la app: si mañana un proveedor nuevo tiene otra manía,
 * se arregla con un deploy del Worker.
 */
export function extraerJson(texto) {
  if (!texto) return null;
  let s = String(texto).trim();
  if (s.startsWith('```')) {
    s = s.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '').trim();
  }
  if (!s.startsWith('{')) {
    const desde = s.indexOf('{');
    const hasta = s.lastIndexOf('}');
    if (desde < 0 || hasta <= desde) return null;
    s = s.slice(desde, hasta + 1);
  }
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}
