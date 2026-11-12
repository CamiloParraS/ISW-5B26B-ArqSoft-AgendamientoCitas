// Cliente HTTPS/JSON hacia la Aplicación de agenda (Django). El token lo emite Identidad.
const CLAVE_TOKEN = 'agenda-token';

let token = null;
try {
  token = sessionStorage.getItem(CLAVE_TOKEN);
} catch {
  token = null;
}

export function guardarToken(nuevo) {
  token = nuevo;
  try {
    if (nuevo) sessionStorage.setItem(CLAVE_TOKEN, nuevo);
    else sessionStorage.removeItem(CLAVE_TOKEN);
  } catch {
    /* sin almacenamiento: la sesión dura lo que dure la pestaña */
  }
}

export function hayToken() {
  return Boolean(token);
}

export async function api(metodo, ruta, cuerpo, cabeceras = {}) {
  const respuesta = await fetch(ruta, {
    method: metodo,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...cabeceras,
    },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  const datos = await respuesta.json().catch(() => ({}));
  if (!respuesta.ok) {
    const error = new Error(datos.error || `Error ${respuesta.status}`);
    error.estado = respuesta.status;
    error.datos = datos;
    throw error;
  }
  return datos;
}

// Clave de idempotencia por solicitud: un doble clic o un reintento reusa la misma.
export function nuevaClave() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const ZONA = 'America/Bogota';
export const fecha = (iso) =>
  new Date(iso).toLocaleDateString('es-CO', { timeZone: ZONA, weekday: 'long', day: 'numeric', month: 'long' });
export const hora = (iso) => new Date(iso).toLocaleTimeString('es-CO', { timeZone: ZONA, hour: '2-digit', minute: '2-digit' });
export const fechaHora = (iso) => `${fecha(iso)} · ${hora(iso)}`;
export const diaClave = (iso) => new Date(iso).toLocaleDateString('en-CA', { timeZone: ZONA });
