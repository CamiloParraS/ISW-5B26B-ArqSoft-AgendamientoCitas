import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Bitacora } from '../types';
import { fechaHora, mayuscula } from '../format';
import { mensajeDeError } from '../mensajes';
import { Aviso, Titulo } from '../components/comunes';

export function QuienConsulto() {
  const [filas, setFilas] = useState<Bitacora[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.bitacora()
      .then((f) => setFilas([...f].sort((a, b) => Date.parse(b.ocurridoEn) - Date.parse(a.ocurridoEn))))
      .catch((e) => { setFilas([]); setError(mensajeDeError(e)); });
  }, []);

  return (
    <section>
      <Titulo>Quién consultó mis datos</Titulo>
      <p>Aquí aparece cada vez que una institución aliada consultó, o intentó consultar, sus citas.</p>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {!filas && <p role="status">Cargando…</p>}
      {filas && filas.length === 0 && !error && <Aviso tipo="info">Nadie ha consultado sus datos todavía.</Aviso>}
      <ul className="lista">
        {filas?.map((f, i) => {
          const denegado = f.resultado === 'DENEGADO';
          return (
            <li key={f.id ?? i} className="tarjeta">
              <p className="grande">{f.institucionNombre ?? f.institucionCodigo ?? 'Institución'}</p>
              <p>{mayuscula(fechaHora(f.ocurridoEn))}</p>
              {f.resultado && (
                <p className={`estado ${denegado ? 'alerta' : ''}`}>
                  {denegado ? 'Consulta bloqueada: no tenía su autorización' : 'Consulta permitida con su autorización'}
                </p>
              )}
            </li>
          );
        })}
      </ul>
      <Link to="/" className="btn texto">Volver al inicio</Link>
    </section>
  );
}
