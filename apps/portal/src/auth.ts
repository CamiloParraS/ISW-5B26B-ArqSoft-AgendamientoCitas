import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

// Keycloak (realm medicita) detrás del gateway: mismo origen, ruta /auth.
export const userManager = new UserManager({
  authority: `${window.location.origin}/auth/realms/medicita`,
  client_id: 'portal',
  redirect_uri: `${window.location.origin}/portal/`,
  post_logout_redirect_uri: `${window.location.origin}/portal/`,
  response_type: 'code', // Authorization Code + PKCE (S256)
  scope: 'openid',
  automaticSilentRenew: false, // renovación manual sólo si hay actividad (ver Inactividad)
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

let redirigiendo = false;
/** Inicia sesión una sola vez (evita varias redirecciones por 401 simultáneos). */
export function iniciarSesion(): void {
  if (redirigiendo) return;
  redirigiendo = true;
  void userManager.signinRedirect();
}

export type Rol = 'medico' | 'administrativo_ips' | 'coordinador';
export const ROLES_PORTAL: Rol[] = ['medico', 'administrativo_ips', 'coordinador'];

export const ETIQUETA_ROL: Record<Rol, string> = {
  medico: 'Médico',
  administrativo_ips: 'Administrativo IPS',
  coordinador: 'Coordinación',
};
export const RUTA_ROL: Record<Rol, string> = {
  medico: '/medico',
  administrativo_ips: '/ips',
  coordinador: '/coordinacion',
};

/** Lee el payload del JWT de acceso (sin validar: la API valida la firma). */
export function payloadDe(token?: string): Record<string, unknown> {
  if (!token) return {};
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64.padEnd(Math.ceil(b64.length / 4) * 4, '='));
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return {};
  }
}

/** Roles de realm_access.roles del access token. */
export function rolesDe(token?: string): string[] {
  const r = (payloadDe(token).realm_access as { roles?: unknown } | undefined)?.roles;
  return Array.isArray(r) ? r.filter((x): x is string => typeof x === 'string') : [];
}
