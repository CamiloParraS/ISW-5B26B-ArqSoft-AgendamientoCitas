import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

// Keycloak (realm medicita) detrás del gateway: mismo origen, ruta /auth.
export const userManager = new UserManager({
  authority: `${window.location.origin}/auth/realms/medicita`,
  client_id: 'app-paciente',
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
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
