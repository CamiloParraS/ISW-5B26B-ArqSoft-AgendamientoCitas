import { iniciarSesion, userManager } from './auth';
import type {
  Bitacora, Cita, Consentimiento, Cupo, Especialidad, Institucion, ResultadoCancelacion, Retencion,
} from './types';

export class ApiError extends Error {
  constructor(
    public status: number,
    public codigo: string,
    mensaje: string,
    public cuerpo: Record<string, unknown> = {},
  ) {
    super(mensaje);
  }
  get alternativas(): Cupo[] {
    const a = this.cuerpo.alternativas;
    return Array.isArray(a) ? (a as Cupo[]) : [];
  }
}

interface Opciones {
  body?: unknown;
  idempotencyKey?: string;
}

async function solicitar<T>(metodo: string, ruta: string, op: Opciones = {}): Promise<T> {
  const usuario = await userManager.getUser();
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-Id': crypto.randomUUID(),
  };
  if (usuario?.access_token) headers.Authorization = `Bearer ${usuario.access_token}`;
  if (op.body !== undefined) headers['Content-Type'] = 'application/json';
  if (op.idempotencyKey) headers['Idempotency-Key'] = op.idempotencyKey;

  let resp: Response;
  try {
    resp = await fetch(`/api${ruta}`, {
      method: metodo,
      headers,
      body: op.body !== undefined ? JSON.stringify(op.body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'SIN_CONEXION', 'No hay conexión con MediCita.');
  }
  if (resp.status === 401) {
    iniciarSesion();
    throw new ApiError(401, 'NO_AUTENTICADO', 'Su sesión terminó. Volviendo a iniciar sesión…');
  }
  const texto = await resp.text();
  let cuerpo: Record<string, unknown> = {};
  try {
    cuerpo = texto ? JSON.parse(texto) : {};
  } catch {
    cuerpo = {};
  }
  if (!resp.ok) {
    throw new ApiError(
      resp.status,
      String(cuerpo.codigo ?? (resp.status === 429 ? 'LIMITE_DE_TASA' : 'ERROR')),
      String(cuerpo.mensaje ?? 'Ocurrió un problema.'),
      cuerpo,
    );
  }
  return (texto ? (JSON.parse(texto) as T) : undefined) as T;
}

const q = encodeURIComponent;

export const api = {
  especialidades: () => solicitar<Especialidad[]>('GET', '/prestadores/especialidades'),
  cuposProximos: (especialidadId: string) =>
    solicitar<Cupo[]>('GET', `/agenda/cupos/proximos?especialidadId=${q(especialidadId)}`),
  cuposDelDia: (especialidadId: string, fecha: string) =>
    solicitar<Cupo[]>('GET', `/agenda/cupos?especialidadId=${q(especialidadId)}&fecha=${q(fecha)}`),
  retener: (cupoId: string) => solicitar<Retencion>('POST', '/agenda/retenciones', { body: { cupoId } }),
  confirmar: (cupoId: string, clave: string) =>
    solicitar<Cita>('POST', '/agenda/citas', { body: { cupoId }, idempotencyKey: clave }),
  misCitas: () => solicitar<Cita[]>('GET', '/agenda/citas/mias'),
  cancelar: (id: string) => solicitar<ResultadoCancelacion>('POST', `/agenda/citas/${q(id)}/cancelacion`),
  reprogramar: (id: string, cupoId: string, clave: string) =>
    solicitar<Cita>('POST', `/agenda/citas/${q(id)}/reprogramacion`, { body: { cupoId }, idempotencyKey: clave }),
  instituciones: () => solicitar<Institucion[]>('GET', '/acceso/instituciones'),
  consentimientos: () => solicitar<Consentimiento[]>('GET', '/acceso/consentimientos'),
  autorizar: (institucionCodigo: string) =>
    solicitar<Consentimiento>('POST', '/acceso/consentimientos', { body: { institucionCodigo } }),
  revocar: (id: string) => solicitar<void>('DELETE', `/acceso/consentimientos/${q(id)}`),
  bitacora: () => solicitar<Bitacora[]>('GET', '/acceso/bitacora/mia'),
};
