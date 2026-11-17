import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Cita } from '../types';
import { fechaHora, mayuscula } from '../format';
import { Titulo } from '../components/comunes';

export function Inicio({ nombre }: { nombre: string }) {
  const [proxima, setProxima] = useState<Cita | null | undefined>(undefined);

  useEffect(() => {
    let vivo = true;
    api.misCitas()
      .then((cs) => {
        const ahora = Date.now();
        const futuras = cs
          .filter((c) => c.estado === 'CONFIRMADA' && Date.parse(c.inicio) > ahora)
          .sort((a, b) => Date.parse(a.inicio) - Date.parse(b.inicio));
        if (vivo) setProxima(futuras[0] ?? null);
      })
      .catch(() => vivo && setProxima(null));
    return () => { vivo = false; };
  }, []);

  return (
    <>
      <Titulo>{nombre ? `Hola, ${nombre}` : 'Hola'}</Titulo>
      {proxima && (
        <section className="tarjeta proxima" aria-label="Su próxima cita">
          <p className="etiqueta">Su próxima cita</p>
          <p className="grande">{mayuscula(fechaHora(proxima.inicio))}</p>
          <p>{proxima.especialidad} · {proxima.medicoNombre}</p>
          <p>{proxima.sedeNombre}</p>
        </section>
      )}
      {proxima === null && <p className="suave">Todavía no tiene citas próximas.</p>}
      <nav className="menu" aria-label="Qué desea hacer">
        <Link to="/agendar" className="btn primario enorme">Agendar una cita</Link>
        <Link to="/citas" className="btn secundario enorme">Mis citas</Link>
        <Link to="/autorizaciones" className="btn secundario enorme">Autorizaciones</Link>
        <Link to="/consultas" className="btn secundario enorme">Quién consultó mis datos</Link>
      </nav>
    </>
  );
}
