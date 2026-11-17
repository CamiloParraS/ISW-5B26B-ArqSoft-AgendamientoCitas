import { useEffect, useRef, useState, type ReactNode } from 'react';

/** Título de pantalla; recibe el foco al cambiar para lectores de pantalla. */
export function Titulo({ children, clave }: { children: ReactNode; clave?: string }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus({ preventScroll: false });
  }, [clave]);
  return (
    <h1 ref={ref} tabIndex={-1} className="titulo">
      {children}
    </h1>
  );
}

const PASOS = ['Especialidad', 'Cupo', 'Confirmar'];

export function Pasos({ actual }: { actual: 1 | 2 | 3 }) {
  return (
    <ol className="pasos" aria-label="Pasos para agendar">
      {PASOS.map((nombre, i) => {
        const n = i + 1;
        const estado = n < actual ? 'hecho' : n === actual ? 'actual' : 'pendiente';
        return (
          <li key={nombre} className={`paso ${estado}`} aria-current={n === actual ? 'step' : undefined}>
            <span className="paso-num" aria-hidden="true">{n < actual ? '✓' : n}</span>
            <span className="paso-nombre">
              <span className="sr-only">Paso {n}: </span>
              {nombre}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/** Cuenta regresiva de la retención del cupo (5 min). */
export function CuentaRegresiva({ hasta, alVencer }: { hasta: number; alVencer: () => void }) {
  const [ahora, setAhora] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setAhora(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  const seg = Math.max(0, Math.ceil((hasta - ahora) / 1000));
  useEffect(() => {
    if (seg === 0) alVencer();
  }, [seg, alVencer]);

  const mm = String(Math.floor(seg / 60)).padStart(2, '0');
  const ss = String(seg % 60).padStart(2, '0');
  const pct = Math.min(100, (seg / 300) * 100);
  const anuncio =
    seg === 0
      ? 'El tiempo terminó. El cupo fue liberado.'
      : seg > 60
        ? `Le quedan ${Math.ceil(seg / 60)} minutos para confirmar.`
        : seg > 30
          ? 'Le queda menos de un minuto para confirmar.'
          : 'Le quedan menos de 30 segundos para confirmar.';
  return (
    <div className={`cuenta ${seg <= 60 ? 'urgente' : ''}`}>
      <p className="cuenta-texto">Su cupo está reservado para usted durante</p>
      <p className="cuenta-reloj" aria-hidden="true">{mm}:{ss}</p>
      <div className="cuenta-barra" aria-hidden="true"><span style={{ width: `${pct}%` }} /></div>
      <p className="sr-only" role="status" aria-live="polite">{anuncio}</p>
    </div>
  );
}

export function Aviso({ tipo, children }: { tipo: 'error' | 'info' | 'ok' | 'alerta'; children: ReactNode }) {
  return (
    <div className={`aviso ${tipo}`} role={tipo === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}
