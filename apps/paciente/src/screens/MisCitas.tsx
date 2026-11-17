import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Cita, ResultadoCancelacion } from '../types';
import { fechaHora, mayuscula } from '../format';
import { mensajeDeError } from '../mensajes';
import { Aviso, Titulo } from '../components/comunes';

// Valor por defecto si la API no informa la política de la sede (/citas/mias la expone por cita).
const HORAS_HABITUALES = 24;

function TarjetaCita({ c, children }: { c: Cita; children?: React.ReactNode }) {
  return (
    <li className="tarjeta cita">
      <p className="grande">{mayuscula(fechaHora(c.inicio))}</p>
      <p>{c.especialidad} · {c.medicoNombre}</p>
      <p>{c.sedeNombre}</p>
      {c.estado !== 'CONFIRMADA' && (
        <p className="etiqueta">{c.estado === 'CANCELADA' ? 'Cancelada' : 'Atendida'}</p>
      )}
      {children}
    </li>
  );
}

export function MisCitas() {
  const [citas, setCitas] = useState<Cita[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [aCancelar, setACancelar] = useState<Cita | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [resultado, setResultado] = useState<{ cita: Cita; r: ResultadoCancelacion } | null>(null);

  const cargar = useCallback(() => {
    api.misCitas().then(setCitas).catch((e) => { setCitas([]); setError(mensajeDeError(e)); });
  }, []);
  useEffect(cargar, [cargar]);

  const cancelar = async () => {
    if (!aCancelar || ocupado) return;
    setOcupado(true);
    setError(null);
    try {
      const r = await api.cancelar(aCancelar.id);
      setResultado({ cita: aCancelar, r });
      setACancelar(null);
      cargar();
    } catch (e) {
      setError(mensajeDeError(e));
    } finally {
      setOcupado(false);
    }
  };

  if (resultado) {
    return (
      <section>
        <Titulo>Su cita fue cancelada</Titulo>
        {resultado.r.conPenalidad ? (
          <Aviso tipo="alerta">
            Como faltaba poco tiempo para la cita, se aplicó una penalidad según la política de la sede.
          </Aviso>
        ) : (
          <Aviso tipo="ok">La cancelación fue sin penalidad.</Aviso>
        )}
        <p>{mayuscula(fechaHora(resultado.cita.inicio))} · {resultado.cita.medicoNombre}</p>
        <button className="btn primario enorme" onClick={() => setResultado(null)}>Ver mis citas</button>
        <Link to="/agendar" className="btn secundario">Agendar otra cita</Link>
      </section>
    );
  }

  if (aCancelar) {
    const minimas = aCancelar.horasMinimasCancelacion ?? HORAS_HABITUALES;
    const conPenalidad = aCancelar.cancelacionConPenalidad
      ?? (Date.parse(aCancelar.inicio) - Date.now()) / 3_600_000 < minimas;
    return (
      <section>
        <Titulo>¿Desea cancelar esta cita?</Titulo>
        <div className="tarjeta resumen">
          <p className="grande">{mayuscula(fechaHora(aCancelar.inicio))}</p>
          <p>{aCancelar.especialidad} · {aCancelar.medicoNombre}</p>
          <p>{aCancelar.sedeNombre}</p>
        </div>
        {conPenalidad ? (
          <Aviso tipo="alerta">
            Atención: faltan menos de {minimas} horas para su cita. Si cancela ahora, se aplicará una
            penalidad según la política de la sede.
          </Aviso>
        ) : (
          <Aviso tipo="info">
            Cancelar con menos de {minimas} horas de anticipación puede tener penalidad. Hoy todavía está a tiempo.
          </Aviso>
        )}
        {error && <Aviso tipo="error">{error}</Aviso>}
        <button className="btn primario enorme" onClick={() => setACancelar(null)} disabled={ocupado}>
          No, conservar mi cita
        </button>
        <button className="btn peligro" onClick={cancelar} disabled={ocupado} aria-busy={ocupado}>
          {ocupado ? 'Cancelando…' : 'Sí, cancelar mi cita'}
        </button>
      </section>
    );
  }

  const ahora = Date.now();
  const proximas = (citas ?? [])
    .filter((c) => c.estado === 'CONFIRMADA' && Date.parse(c.inicio) > ahora)
    .sort((a, b) => Date.parse(a.inicio) - Date.parse(b.inicio));
  const pasadas = (citas ?? [])
    .filter((c) => !proximas.includes(c))
    .sort((a, b) => Date.parse(b.inicio) - Date.parse(a.inicio));

  return (
    <section>
      <Titulo>Mis citas</Titulo>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {!citas && <p role="status">Cargando sus citas…</p>}
      {citas && proximas.length === 0 && (
        <>
          <p>No tiene citas próximas.</p>
          <Link to="/agendar" className="btn primario enorme">Agendar una cita</Link>
        </>
      )}
      {proximas.length > 0 && (
        <>
          <h2 className="subtitulo">Próximas</h2>
          <ul className="lista">
            {proximas.map((c) => (
              <TarjetaCita key={c.id} c={c}>
                <div className="fila">
                  <Link to={`/citas/${c.id}/reprogramar`} className="btn secundario">Cambiar fecha</Link>
                  <button className="btn peligro-suave" onClick={() => { setError(null); setACancelar(c); }}>
                    Cancelar cita
                  </button>
                </div>
              </TarjetaCita>
            ))}
          </ul>
        </>
      )}
      {pasadas.length > 0 && (
        <>
          <h2 className="subtitulo">Anteriores</h2>
          <ul className="lista">
            {pasadas.map((c) => <TarjetaCita key={c.id} c={c} />)}
          </ul>
        </>
      )}
      <Link to="/" className="btn texto">Volver al inicio</Link>
    </section>
  );
}
