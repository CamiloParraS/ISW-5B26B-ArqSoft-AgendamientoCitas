import { useEffect, useState, type ReactNode } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { Inicio } from './screens/Inicio';
import { Agendar } from './screens/Agendar';
import { MisCitas } from './screens/MisCitas';
import { Autorizaciones } from './screens/Autorizaciones';
import { QuienConsulto } from './screens/QuienConsulto';

function nombreDe(auth: ReturnType<typeof useAuth>): string {
  const p = auth.user?.profile as Record<string, unknown> | undefined;
  const n = (p?.name ?? p?.given_name ?? p?.preferred_username ?? '') as string;
  return n.split(' ')[0] ?? '';
}

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

  // Sesión vencida o renovación fallida: pedir ingreso de nuevo
  useEffect(() => auth.events.addSilentRenewError(() => void signinRedirect()), [auth.events, signinRedirect]);

  if (error)
    return (
      <main className="centro">
        <h1 className="titulo">No pudimos iniciar su sesión</h1>
        <p>Puede intentar de nuevo. Si el problema sigue, pida ayuda en su IPS.</p>
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
        <p role="status">Entrando a MediCita…</p>
      </main>
    );
  return (
    <>
      <Inactividad />
      {children}
    </>
  );
}

export function App() {
  const auth = useAuth();
  return (
    <Puerta>
      <a className="salto" href="#contenido">Saltar al contenido</a>
      <header className="cabecera">
        <Link to="/" className="marca" aria-label="MediCita, ir al inicio">
          <img src="/icon.svg" alt="" width="36" height="36" />
          <span>MediCita</span>
        </Link>
        <button className="btn texto" onClick={() => void auth.signoutRedirect()}>
          Salir
        </button>
      </header>
      <main id="contenido" className="contenido">
        <Routes>
          <Route path="/" element={<Inicio nombre={nombreDe(auth)} />} />
          <Route path="/agendar" element={<Agendar />} />
          <Route path="/citas" element={<MisCitas />} />
          <Route path="/citas/:id/reprogramar" element={<Agendar />} />
          <Route path="/autorizaciones" element={<Autorizaciones />} />
          <Route path="/consultas" element={<QuienConsulto />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </Puerta>
  );
}
