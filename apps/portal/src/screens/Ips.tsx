import { useRef, useState, type FormEvent } from 'react';
import { api, ApiError, textoDeError, type FilaConsulta } from '../api';
import { Aviso } from '../components/Aviso';

export function Ips() {
  const [documento, setDocumento] = useState('');
  const [filas, setFilas] = useState<FilaConsulta[] | null>(null);
  const [consultado, setConsultado] = useState('');
  const [error, setError] = useState<{ tipo: 'alerta' | 'error' | 'info'; texto: string } | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const resultado = useRef<HTMLDivElement>(null);

  const consultar = async (ev: FormEvent) => {
    ev.preventDefault();
    const doc = documento.trim();
    if (!doc || ocupado) return;
    setOcupado(true);
    setError(null);
    setFilas(null);
    try {
      setFilas(await api.consultarCitas(doc));
      setConsultado(doc);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403)
        setError({ tipo: 'alerta', texto: 'El paciente no ha autorizado a su institución' });
      else if (e instanceof ApiError && e.status === 404)
        setError({ tipo: 'info', texto: 'No encontramos un paciente con ese documento' });
      else setError({ tipo: 'error', texto: textoDeError(e) });
    } finally {
      setOcupado(false);
      resultado.current?.focus();
    }
  };

  return (
    <section>
      <h1 className="titulo">Consultar citas de un paciente</h1>
      <form onSubmit={consultar} className="barra">
        <div>
          <label htmlFor="documento">Número de documento del paciente</label>
          <input
            id="documento" inputMode="numeric" autoComplete="off" required value={documento}
            onChange={(e) => setDocumento(e.target.value)} placeholder="Ej. 1085000001"
          />
        </div>
        <button className="btn primario" type="submit" disabled={ocupado || !documento.trim()}>
          {ocupado ? 'Consultando…' : 'Consultar'}
        </button>
      </form>
      <p className="nota">
        Cada consulta queda registrada en la bitácora de auditoría (Ley 1581 de 2012): quién consultó, a quién y cuándo.
      </p>

      <div ref={resultado} tabIndex={-1} className="resultado" aria-live="polite">
        {error && <Aviso tipo={error.tipo}>{error.texto}</Aviso>}
        {filas && filas.length === 0 && (
          <Aviso tipo="info">El paciente {consultado} no tiene citas vigentes.</Aviso>
        )}
        {filas && filas.length > 0 && (
          <div className="tabla-marco">
            <table>
              <caption className="sr-only">Citas vigentes del paciente {consultado}</caption>
              <thead>
                <tr><th scope="col">Fecha</th><th scope="col">Hora</th><th scope="col">Sede</th><th scope="col">Médico</th></tr>
              </thead>
              <tbody>
                {filas.map((f, i) => (
                  <tr key={`${f.fecha}-${f.hora}-${i}`}>
                    <td>{f.fecha}</td><td>{f.hora}</td><td>{f.sede}</td><td>{f.medico}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
