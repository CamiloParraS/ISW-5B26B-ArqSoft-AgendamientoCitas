import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { ETIQUETA_ROL, payloadDe, ROLES_PORTAL, RUTA_ROL, rolesDe, type Rol } from './auth';
import { Medico } from './screens/Medico';
import { Ips } from './screens/Ips';
import { Coordinacion } from './screens/Coordinacion';

import { Inactividad, CLAVE_AVISO } from './components/Inactividad';

function Puerta({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const { isAuthenticated, isLoading, activeNavigator, error, signinRedirect } = auth;

  const [cerrada, setCerrada] = useState(() => {
    try { return sessionStorage.getItem(CLAVE_AVISO) === '1'; } catch { return false; }
  });
  useEffect(() => {
    if (isAuthenticated) try { sessionStorage.removeItem(CLAVE_AVISO); } catch { /* sin storage */ }
  }, [isAuthenticated]);

  useEffect(() => {
    if (!isAuthenticated && !isLoading && !activeNavigator && !error && !cerrada) void signinRedirect();
  }, [isAuthenticated, isLoading, activeNavigator, error, cerrada, signinRedirect]);

  useEffect(() => auth.events.addSilentRenewError(() => void signinRedirect()), [auth.events, signinRedirect]);

  if (error)
    return (
      <main className="centro">
        <h1 className="titulo">No se pudo iniciar la sesión</h1>
        <button className="btn primario" onClick={() => void signinRedirect()}>Ingresar de nuevo</button>
      </main>
    );
  if (!isAuthenticated && cerrada && !isLoading)
    return (
      <main className="centro">
        <h1 className="titulo">Sesión cerrada</h1>
        <p role="status">Por seguridad cerramos su sesión después de 15 minutos sin uso.</p>
        <button
          className="btn primario"
          onClick={() => {
            try { sessionStorage.removeItem(CLAVE_AVISO); } catch { /* sin storage */ }
            setCerrada(false);
          }}
        >
          Ingresar de nuevo
        </button>
      </main>
    );
  if (!isAuthenticated)
    return (
      <main className="centro" aria-busy="true">
        <p role="status">Entrando al portal…</p>
      </main>
    );
  return (
    <>
      <Inactividad />
      {children}
    </>
  );
}

function Inicio({ roles }: { roles: Rol[] }) {
  if (roles.length === 1) return <Navigate to={RUTA_ROL[roles[0]]} replace />;
  if (roles.length === 0)
    return (
      <section>
        <h1 className="titulo">Sin acceso al portal</h1>
        <p>
          Su usuario no tiene un rol clínico o administrativo. Si es paciente, use la{' '}
          <a href="/">app del paciente</a>.
        </p>
      </section>
    );
  return (
    <section>
      <h1 className="titulo">¿Con qué rol desea trabajar?</h1>
      <p className="suave">Su usuario tiene varios roles. Elija uno; puede cambiar luego desde el menú.</p>
      <div className="selector">
        {roles.map((r) => (
          <NavLink key={r} to={RUTA_ROL[r]} className="btn primario">{ETIQUETA_ROL[r]}</NavLink>
        ))}
      </div>
    </section>
  );
}

function Protegida({ rol, roles, children }: { rol: Rol; roles: Rol[]; children: ReactNode }) {
  return roles.includes(rol) ? <>{children}</> : <Navigate to="/" replace />;
}

export function App() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const roles = useMemo(() => ROLES_PORTAL.filter((r) => rolesDe(token).includes(r)), [token]);
  const p = payloadDe(token);
  const nombre = String(p.name ?? p.preferred_username ?? '');

  return (
    <Puerta>
      <a className="salto" href="#contenido">Saltar al contenido</a>
      <header className="cabecera">
        <NavLink to="/" className="marca" aria-label="MediCita portal, inicio">
          <img src="/portal/icon.svg" alt="" width="30" height="30" />
          <span>MediCita <em>Portal</em></span>
        </NavLink>
        {roles.length > 1 && (
          <nav aria-label="Roles" className="roles">
            {roles.map((r) => (
              <NavLink key={r} to={RUTA_ROL[r]} className={({ isActive }) => `rol ${isActive ? 'activo' : ''}`}>
                {ETIQUETA_ROL[r]}
              </NavLink>
            ))}
          </nav>
        )}
        <div className="usuario">
          <span>{nombre}</span>
          <button className="btn texto" onClick={() => void auth.signoutRedirect()}>Salir</button>
        </div>
      </header>
      <main id="contenido" className="contenido">
        <Routes>
          <Route path="/" element={<Inicio roles={roles} />} />
          <Route path="/medico" element={<Protegida rol="medico" roles={roles}><Medico /></Protegida>} />
          <Route path="/ips" element={<Protegida rol="administrativo_ips" roles={roles}><Ips /></Protegida>} />
          <Route path="/coordinacion" element={<Protegida rol="coordinador" roles={roles}><Coordinacion /></Protegida>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </Puerta>
  );
}
