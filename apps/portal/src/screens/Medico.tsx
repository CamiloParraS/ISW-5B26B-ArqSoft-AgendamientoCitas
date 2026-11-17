import { useCallback, useEffect, useState } from 'react';
import { api, textoDeError, type CupoAgenda } from '../api';
import { hoyIso, hora } from '../format';
import { Aviso } from '../components/Aviso';

const ETIQUETA: Record<string, string> = {
  LIBRE: 'Libre', RETENIDO: 'Retenido (paciente confirmando)', RESERVADO: 'Reservado', BLOQUEADO: 'Bloqueado',
};

export function Medico() {
  const [fecha, setFecha] = useState(hoyIso());
  const [cupos, setCupos] = useState<CupoAgenda[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    setError(null);
    try {
      const l = await api.agendaDelDia(fecha);
      setCupos([...l].sort((a, b) => Date.parse(a.inicio) - Date.parse(b.inicio)));
    } catch (e) {
      setCupos([]);
      setError(textoDeError(e));
    }
  }, [fecha]);

  useEffect(() => { setCupos(null); void cargar(); }, [cargar]);

  const accion = async (cupo: CupoAgenda, tipo: 'bloquear' | 'desbloquear' | 'atender') => {
    setOcupado(cupo.id);
    setError(null);
    setOk(null);
    try {
      if (tipo === 'bloquear') await api.bloquear(cupo.id);
      else if (tipo === 'desbloquear') await api.desbloquear(cupo.id);
      else {
        const citaId = cupo.citaId ?? cupo.cita?.id;
        if (!citaId) throw new Error('sin cita');
        await api.atender(citaId);
      }
      setOk(
        tipo === 'bloquear' ? `Horario de las ${hora(cupo.inicio)} bloqueado.`
          : tipo === 'desbloquear' ? `Horario de las ${hora(cupo.inicio)} liberado.`
            : `Cita de las ${hora(cupo.inicio)} marcada como atendida.`,
      );
      await cargar();
    } catch (e) {
      setError(textoDeError(e));
    } finally {
      setOcupado(null);
    }
  };

  return (
    <section>
      <h1 className="titulo">Mi agenda del día</h1>
      <div className="barra">
        <div>
          <label htmlFor="fecha">Fecha</label>
          <input id="fecha" type="date" value={fecha} onChange={(e) => e.target.value && setFecha(e.target.value)} />
        </div>
        <button className="btn" onClick={() => setFecha(hoyIso())}>Hoy</button>
        <button className="btn" onClick={() => void cargar()}>Actualizar</button>
      </div>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}
      {!cupos && <p role="status">Cargando agenda…</p>}
      {cupos && cupos.length === 0 && !error && <Aviso tipo="info">No hay horarios publicados para este día.</Aviso>}
      {cupos && cupos.length > 0 && (
        <div className="tabla-marco">
          <table>
            <caption className="sr-only">Cupos del día</caption>
            <thead>
              <tr><th scope="col">Hora</th><th scope="col">Estado</th><th scope="col">Paciente</th><th scope="col">Acción</th></tr>
            </thead>
            <tbody>
              {cupos.map((c) => {
                const citaId = c.citaId ?? c.cita?.id;
                const paciente = c.pacienteNombre ?? c.cita?.pacienteNombre ?? '';
                const atendida = (c.citaEstado ?? c.cita?.estado) === 'ATENDIDA';
                return (
                  <tr key={c.id}>
                    <td>{hora(c.inicio)} – {hora(c.fin)}</td>
                    <td>
                      <span className={`chip ${c.estado.toLowerCase()}`}>
                        {atendida ? 'Atendida' : (ETIQUETA[c.estado] ?? c.estado)}
                      </span>
                    </td>
                    <td>{paciente || '—'}</td>
                    <td>
                      {c.estado === 'LIBRE' && (
                        <button className="btn" disabled={ocupado !== null} onClick={() => accion(c, 'bloquear')}>Bloquear</button>
                      )}
                      {c.estado === 'BLOQUEADO' && (
                        <button className="btn" disabled={ocupado !== null} onClick={() => accion(c, 'desbloquear')}>Desbloquear</button>
                      )}
                      {c.estado === 'RESERVADO' && citaId && !atendida && (
                        <button className="btn primario" disabled={ocupado !== null} onClick={() => accion(c, 'atender')}>
                          Marcar atendida
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
