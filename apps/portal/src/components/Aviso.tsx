import type { ReactNode } from 'react';

export function Aviso({ tipo, children }: { tipo: 'error' | 'info' | 'ok' | 'alerta'; children: ReactNode }) {
  return (
    <div className={`aviso ${tipo}`} role={tipo === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}
