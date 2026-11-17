import { iniciarSesion, userManager } from './auth';

export class ApiError extends Error {
  constructor(
    public status: number,
    public codigo: string,
    mensaje: string,
    public cuerpo: Record<string, unknown> = {},
  ) {
    super(mensaje);
  }
}

async function solicitar<T>(metodo: string, ruta: string, body?: unknown): Promise<T> {
  const usuario = await userManager.getUser();
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-Id': crypto.randomUUID(),
  };
  if (usuario?.access_token) headers.Authorization = `Bearer ${usuario.access_token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let resp: Response;
  try {
    resp = await fetch(`/api${ruta}`, {
      method: metodo,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'SIN_CONEXION', 'No hay conexión con MediCita. Revise la red e intente de nuevo.');
  }
  if (resp.status === 401) {
    iniciarSesion();
    throw new ApiError(401, 'NO_AUTENTICADO', 'La sesión terminó. Redirigiendo al inicio de sesión…');
  }
  const texto = await resp.text();
  let cuerpo: unknown = undefined;
  try {
    cuerpo = texto ? JSON.parse(texto) : undefined;
  } catch {
    cuerpo = undefined;
  }
  if (!resp.ok) {
    const e = (cuerpo ?? {}) as Record<string, unknown>;
    throw new ApiError(
      resp.status,
      String(e.codigo ?? (resp.status === 429 ? 'LIMITE_DE_TASA' : 'ERROR')),
      String(e.mensaje ?? 'Ocurrió un problema.'),
      e,
    );
  }
  return cuerpo as T;
}

export function textoDeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return 'Demasiadas solicitudes. Espere unos segundos e intente de nuevo.';
    if (e.status === 403) return 'No tiene permiso para esta operación.';
    return e.message;
  }
  return 'Algo salió mal. Intente de nuevo.';
}

const q = encodeURIComponent;

// ---- Tipos ----
export interface CupoAgenda {
  id: string; inicio: string; fin: string; estado: 'LIBRE' | 'RETENIDO' | 'RESERVADO' | 'BLOQUEADO' | string;
  citaId?: string; pacienteNombre?: string; citaEstado?: string;
  cita?: { id: string; estado?: string; pacienteNombre?: string };
}
export interface FilaConsulta { fecha: string; hora: string; sede: string; medico: string }
export interface Sede { id: string; nombre: string }
export interface Especialidad { id: string; nombre: string }
export interface MedicoLista { id: string; nombre: string; especialidadId: string; especialidad: string; sedeId: string; sede: string }
export interface Politica { sedeId: string; sedeNombre?: string; horasMinimas: number }
export interface FilaOcupacion {
  medicoId: string; medicoNombre: string; totalCupos: number; reservados: number; atendidos: number; porcentaje: number;
}
export interface ReporteOcupacion { mes: string; medicos: FilaOcupacion[] }
export interface EstadoOutbox { pendientes: number; antiguedadMasAntiguaSegundos: number; alarma: boolean }
export interface Horario {
  medicoId: string; desde: string; hasta: string; diasSemana: number[]; // ISO: 1 = lunes ... 7 = domingo
  horaInicio: string; horaFin: string; duracionMinutos: number;
}

export const api = {
  // Médico
  agendaDelDia: (fecha: string) => solicitar<CupoAgenda[]>('GET', `/agenda/medico/agenda?fecha=${q(fecha)}`),
  bloquear: (cupoId: string) => solicitar<void>('POST', `/agenda/medico/cupos/${q(cupoId)}/bloqueo`),
  desbloquear: (cupoId: string) => solicitar<void>('DELETE', `/agenda/medico/cupos/${q(cupoId)}/bloqueo`),
  atender: (citaId: string) => solicitar<unknown>('POST', `/agenda/medico/citas/${q(citaId)}/atencion`),
  // Administrativo IPS
  consultarCitas: (documento: string) =>
    solicitar<FilaConsulta[]>('GET', `/acceso/consultas/citas?documento=${q(documento)}`),
  // Coordinación
  sedes: () => solicitar<Sede[]>('GET', '/prestadores/sedes'),
  especialidades: () => solicitar<Especialidad[]>('GET', '/prestadores/especialidades'),
  medicos: (especialidadId: string) =>
    solicitar<MedicoLista[]>('GET', `/prestadores/medicos?especialidadId=${q(especialidadId)}`),
  politicas: () => solicitar<Politica[]>('GET', '/agenda/politicas-cancelacion'),
  guardarPolitica: (sedeId: string, horasMinimas: number) =>
    solicitar<unknown>('PUT', `/agenda/politicas-cancelacion/${q(sedeId)}`, { horasMinimas }),
  publicarHorario: (h: Horario) => solicitar<unknown>('POST', '/prestadores/horarios', h),
  ocupacion: (mes: string) => solicitar<ReporteOcupacion>('GET', `/agenda/reportes/ocupacion?mes=${q(mes)}`),
  outbox: () => solicitar<EstadoOutbox>('GET', '/agenda/operacion/outbox'),
};
