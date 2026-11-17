import { useCallback, useEffect, useRef, useState } from 'react';
import { iniciarSesion, userManager } from '../auth';

// Sesiones inactivas se cierran a los 15 min; aviso 1 min antes.
export const LIMITE_MS = 15 * 60_000;
const AVISO_MS = LIMITE_MS - 60_000;
export const CLAVE_AVISO = 'medicita.sesion.cerrada';

/** Vigila la actividad del usuario: renueva el token sólo si hubo uso y cierra la sesión por inactividad. */
export function Inactividad() {
  const ultima = useRef(Date.now());
  const cerrando = useRef(false);
  const renovando = useRef(false);
  const [aviso, setAviso] = useState(false);

  const renovar = useCallback(async () => {
    if (renovando.current) return;
    renovando.current = true;
    try {
      await userManager.signinSilent();
    } catch {
      iniciarSesion();
    } finally {
      renovando.current = false;
    }
  }, []);

  const actividad = useCallback(() => {
    const ahora = Date.now();
    if (ahora - ultima.current >= LIMITE_MS) return; // ya venció: no se revive
    if (ahora - ultima.current < 2000 && !document.hidden) return;
    ultima.current = ahora;
    setAviso(false);
    void userManager.getUser().then((u) => {
      if (u && (u.expired || (u.expires_in ?? 999) < 60)) void renovar();
    });
  }, [renovar]);

  useEffect(() => {
    const eventos = ['pointerdown', 'pointermove', 'keydown', 'touchstart', 'scroll', 'wheel'] as const;
    eventos.forEach((e) => window.addEventListener(e, actividad, { passive: true }));
    const visible = () => { if (!document.hidden) actividad(); };
    document.addEventListener('visibilitychange', visible);

    // Renovación manual: sólo si el usuario estuvo activo en los últimos 15 min
    const quitar = userManager.events.addAccessTokenExpiring(() => {
      if (Date.now() - ultima.current < LIMITE_MS) void renovar();
    });

    const t = window.setInterval(() => {
      const ocioso = Date.now() - ultima.current;
      if (ocioso >= LIMITE_MS) {
        if (cerrando.current) return;
        cerrando.current = true;
        try { sessionStorage.setItem(CLAVE_AVISO, '1'); } catch { /* sin storage */ }
        userManager.signoutRedirect().catch(() => userManager.removeUser().then(() => window.location.reload()));
      } else {
        setAviso(ocioso >= AVISO_MS);
      }
    }, 1000);

    return () => {
      eventos.forEach((e) => window.removeEventListener(e, actividad));
      document.removeEventListener('visibilitychange', visible);
      quitar();
      window.clearInterval(t);
    };
  }, [actividad, renovar]);

  if (!aviso) return null;
  return (
    <div className="inactividad" role="alert">
      <p>Por seguridad cerraremos su sesión en 1 minuto por falta de uso.</p>
      <button className="btn primario" onClick={() => { ultima.current = Date.now() - 5000; actividad(); }}>
        Seguir conectado
      </button>
    </div>
  );
}
