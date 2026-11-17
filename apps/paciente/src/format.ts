const ZONA = 'America/Bogota';

export const fechaLarga = (iso: string) =>
  new Intl.DateTimeFormat('es-CO', { weekday: 'long', day: 'numeric', month: 'long', timeZone: ZONA }).format(new Date(iso));

export const hora = (iso: string) =>
  new Intl.DateTimeFormat('es-CO', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: ZONA }).format(new Date(iso));

export const fechaHora = (iso: string) => `${fechaLarga(iso)}, ${hora(iso)}`;

export const fechaCorta = (iso: string) =>
  new Intl.DateTimeFormat('es-CO', { day: 'numeric', month: 'short', year: 'numeric', timeZone: ZONA }).format(new Date(iso));

/** Fecha de hoy en Bogotá como YYYY-MM-DD (para input type=date). */
export const hoyIso = () => new Intl.DateTimeFormat('en-CA', { timeZone: ZONA }).format(new Date());

export const mayuscula = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
