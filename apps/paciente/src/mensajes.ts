import { ApiError } from './api';

/** Texto claro en español para cada tipo de fallo. */
export function mensajeDeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429)
      return 'Hay mucha gente usando MediCita en este momento. No se perdió nada: espere unos segundos y toque el botón otra vez.';
    if (e.status === 0)
      return 'No hay conexión con MediCita. Revise su internet y vuelva a tocar el botón. Sus datos no se perdieron.';
    if (e.status === 401) return e.message;
    if (e.status >= 500)
      return 'MediCita tuvo un problema momentáneo. Espere un momento y vuelva a intentar; no se duplicará su solicitud.';
    return e.message;
  }
  return 'Algo salió mal. Intente de nuevo.';
}
