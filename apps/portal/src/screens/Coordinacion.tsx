import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, textoDeError, type EstadoOutbox, type FilaOcupacion, type Horario, type MedicoLista, type Politica, type Sede } from '../api';
import { hoyIso } from '../format';
import { Aviso } from '../components/Aviso';

type Pestana = 'politicas' | 'horario' | 'ocupacion' | 'outbox';
const PESTANAS: { id: Pestana; nombre: string }[] = [
  { id: 'politicas', nombre: 'Políticas de cancelación' },
  { id: 'horario', nombre: 'Publicar horario' },
  { id: 'ocupacion', nombre: 'Reporte de ocupación' },
  { id: 'outbox', nombre: 'Estado de la outbox' },
];

export function Coordinacion() {
  const [pestana, setPestana] = useState<Pestana>('politicas');
  return (
    <section>
      <h1 className="titulo">Coordinación de agendas</h1>
      <div role="tablist" aria-label="Secciones de coordinación" className="pestanas">
        {PESTANAS.map((p) => (
          <button
            key={p.id} role="tab" id={`tab-${p.id}`} aria-selected={pestana === p.id} aria-controls={`panel-${p.id}`}
            className={`pestana ${pestana === p.id ? 'activa' : ''}`} onClick={() => setPestana(p.id)}
          >
            {p.nombre}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${pestana}`} aria-labelledby={`tab-${pestana}`}>
        {pestana === 'politicas' && <Politicas />}
        {pestana === 'horario' && <PublicarHorario />}
        {pestana === 'ocupacion' && <Ocupacion />}
        {pestana === 'outbox' && <Outbox />}
      </div>
    </section>
  );
}

function Politicas() {
  const [sedes, setSedes] = useState<Sede[]>([]);
  const [pol, setPol] = useState<Politica[] | null>(null);
  const [edit, setEdit] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    try {
      const [s, p] = await Promise.all([api.sedes(), api.politicas()]);
      setSedes(s);
      setPol(p);
      setEdit(Object.fromEntries(p.map((x) => [x.sedeId, String(x.horasMinimas)])));
    } catch (e) {
      setPol([]);
      setError(textoDeError(e));
    }
  }, []);
  useEffect(() => { void cargar(); }, [cargar]);

  const nombreSede = (p: Politica) => p.sedeNombre ?? sedes.find((s) => s.id === p.sedeId)?.nombre ?? p.sedeId;

  const guardar = async (p: Politica) => {
    const horas = Number(edit[p.sedeId]);
    if (!Number.isInteger(horas) || horas < 0 || horas > 720) {
      setError('Escriba un número entero de horas entre 0 y 720.');
      return;
    }
    setOcupado(p.sedeId);
    setError(null);
    setOk(null);
    try {
      await api.guardarPolitica(p.sedeId, horas);
      setOk(`Política de ${nombreSede(p)} actualizada: ${horas} h. Aplica de inmediato.`);
      await cargar();
    } catch (e) {
      setError(textoDeError(e));
    } finally {
      setOcupado(null);
    }
  };

  return (
    <>
      <p className="nota">Horas mínimas de anticipación para cancelar sin penalidad. El cambio rige de inmediato, sin redesplegar.</p>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}
      {!pol && <p role="status">Cargando…</p>}
      {pol && (
        <div className="tabla-marco">
          <table>
            <thead><tr><th scope="col">Sede</th><th scope="col">Horas mínimas</th><th scope="col">Acción</th></tr></thead>
            <tbody>
              {pol.map((p) => (
                <tr key={p.sedeId}>
                  <td>{nombreSede(p)}</td>
                  <td>
                    <label className="sr-only" htmlFor={`h-${p.sedeId}`}>Horas mínimas de {nombreSede(p)}</label>
                    <input
                      id={`h-${p.sedeId}`} type="number" min={0} max={720} step={1} className="corto"
                      value={edit[p.sedeId] ?? ''} onChange={(e) => setEdit({ ...edit, [p.sedeId]: e.target.value })}
                    />
                  </td>
                  <td>
                    <button className="btn primario" disabled={ocupado !== null || edit[p.sedeId] === String(p.horasMinimas)} onClick={() => guardar(p)}>
                      {ocupado === p.sedeId ? 'Guardando…' : 'Guardar'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

const DIAS = [
  { v: 1, n: 'Lun' }, { v: 2, n: 'Mar' }, { v: 3, n: 'Mié' }, { v: 4, n: 'Jue' },
  { v: 5, n: 'Vie' }, { v: 6, n: 'Sáb' }, { v: 7, n: 'Dom' },
];

function PublicarHorario() {
  const [medicos, setMedicos] = useState<MedicoLista[] | null>(null);
  const [medicoId, setMedicoId] = useState('');
  const [desde, setDesde] = useState(hoyIso());
  const [hasta, setHasta] = useState(hoyIso());
  const [dias, setDias] = useState<number[]>([1, 2, 3, 4, 5]);
  const [horaInicio, setHoraInicio] = useState('07:00');
  const [horaFin, setHoraFin] = useState('12:00');
  const [duracion, setDuracion] = useState('20');
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const esps = await api.especialidades();
        const listas = await Promise.all(esps.map((e) => api.medicos(e.id)));
        const todos = listas.flat();
        setMedicos(todos);
        if (todos[0]) setMedicoId(todos[0].id);
      } catch (e) {
        setMedicos([]);
        setError(textoDeError(e));
      }
    })();
  }, []);

  const enviar = async (ev: FormEvent) => {
    ev.preventDefault();
    const m = medicos?.find((x) => x.id === medicoId);
    if (!m) return;
    if (hasta < desde) return setError('La fecha final no puede ser anterior a la inicial.');
    if (horaFin <= horaInicio) return setError('La hora final debe ser posterior a la inicial.');
    if (dias.length === 0) return setError('Seleccione al menos un día de la semana.');
    const h: Horario = {
      medicoId: m.id, desde, hasta, diasSemana: [...dias].sort(),
      horaInicio, horaFin, duracionMinutos: Number(duracion),
    };
    setOcupado(true);
    setError(null);
    setOk(null);
    try {
      await api.publicarHorario(h);
      setOk(`Horario publicado para ${m.nombre}. Los cupos se generan automáticamente.`);
    } catch (e) {
      setError(textoDeError(e));
    } finally {
      setOcupado(false);
    }
  };

  return (
    <form onSubmit={enviar} className="formulario">
      {error && <Aviso tipo="error">{error}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}
      <div className="campo">
        <label htmlFor="medico">Médico</label>
        <select id="medico" value={medicoId} onChange={(e) => setMedicoId(e.target.value)} required disabled={!medicos}>
          {medicos?.map((m) => <option key={m.id} value={m.id}>{m.nombre} · {m.especialidad} · {m.sede}</option>)}
        </select>
      </div>
      <div className="rejilla">
        <div className="campo"><label htmlFor="desde">Desde</label>
          <input id="desde" type="date" value={desde} onChange={(e) => setDesde(e.target.value)} required /></div>
        <div className="campo"><label htmlFor="hasta">Hasta</label>
          <input id="hasta" type="date" value={hasta} min={desde} onChange={(e) => setHasta(e.target.value)} required /></div>
        <div className="campo"><label htmlFor="hi">Hora de inicio</label>
          <input id="hi" type="time" value={horaInicio} onChange={(e) => setHoraInicio(e.target.value)} required /></div>
        <div className="campo"><label htmlFor="hf">Hora de fin</label>
          <input id="hf" type="time" value={horaFin} onChange={(e) => setHoraFin(e.target.value)} required /></div>
        <div className="campo"><label htmlFor="dur">Duración del cupo (min)</label>
          <input id="dur" type="number" min={5} max={240} step={5} value={duracion} onChange={(e) => setDuracion(e.target.value)} required /></div>
      </div>
      <fieldset className="campo">
        <legend>Días de la semana</legend>
        <div className="dias">
          {DIAS.map((d) => (
            <label key={d.v} className="dia">
              <input
                type="checkbox" checked={dias.includes(d.v)}
                onChange={(e) => setDias(e.target.checked ? [...dias, d.v] : dias.filter((x) => x !== d.v))}
              />
              {d.n}
            </label>
          ))}
        </div>
      </fieldset>
      <button className="btn primario" type="submit" disabled={ocupado || !medicoId}>
        {ocupado ? 'Publicando…' : 'Publicar horario'}
      </button>
    </form>
  );
}

function mesActual() {
  return hoyIso().slice(0, 7);
}

function Ocupacion() {
  const [mes, setMes] = useState(mesActual());
  const [filas, setFilas] = useState<FilaOcupacion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  const generar = async (ev?: FormEvent) => {
    ev?.preventDefault();
    if (!mes) return;
    setOcupado(true);
    setError(null);
    setFilas(null);
    try {
      setFilas((await api.ocupacion(mes)).medicos);
    } catch (e) {
      setError(textoDeError(e));
    } finally {
      setOcupado(false);
    }
  };

  return (
    <>
      <form onSubmit={generar} className="barra">
        <div>
          <label htmlFor="mes">Mes</label>
          <input id="mes" type="month" value={mes} onChange={(e) => setMes(e.target.value)} required />
        </div>
        <button className="btn primario" type="submit" disabled={ocupado}>{ocupado ? 'Generando…' : 'Generar reporte'}</button>
      </form>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {filas && filas.length === 0 && <Aviso tipo="info">No hay cupos en ese mes.</Aviso>}
      {filas && filas.length > 0 && (
        <div className="tabla-marco">
          <table>
            <caption className="sr-only">Ocupación por médico en {mes}</caption>
            <thead>
              <tr>
                <th scope="col">Médico</th><th scope="col" className="num">Cupos</th>
                <th scope="col" className="num">Reservados</th><th scope="col" className="num">Atendidos</th>
                <th scope="col" className="num">Ocupación</th>
              </tr>
            </thead>
            <tbody>
              {filas.map((f) => (
                <tr key={f.medicoId}>
                  <td>{f.medicoNombre}</td>
                  <td className="num">{f.totalCupos}</td>
                  <td className="num">{f.reservados}</td>
                  <td className="num">{f.atendidos}</td>
                  <td className="num">{`${f.porcentaje.toFixed(1)} %`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function duracion(seg: number) {
  if (seg < 60) return `${seg} s`;
  if (seg < 3600) return `${Math.floor(seg / 60)} min ${seg % 60} s`;
  return `${Math.floor(seg / 3600)} h ${Math.floor((seg % 3600) / 60)} min`;
}

function Outbox() {
  const [e, setE] = useState<EstadoOutbox | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actualizado, setActualizado] = useState<Date | null>(null);

  const cargar = useCallback(async () => {
    try {
      setE(await api.outbox());
      setActualizado(new Date());
      setError(null);
    } catch (x) {
      setError(textoDeError(x));
    }
  }, []);
  useEffect(() => {
    void cargar();
    const t = window.setInterval(() => void cargar(), 15000);
    return () => window.clearInterval(t);
  }, [cargar]);

  return (
    <>
      <p className="nota">
        La outbox guarda los eventos de citas hasta que el broker los recibe. Si el evento más antiguo supera 5 minutos
        se activa la alarma. Se actualiza cada 15 segundos.
      </p>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {e && (
        <>
          {e.alarma
            ? <Aviso tipo="error"><strong>ALARMA: outbox atrasada.</strong> Hay eventos sin publicar desde hace más de 5 minutos. Revise el broker (RabbitMQ).</Aviso>
            : <Aviso tipo="ok"><strong>Outbox al día.</strong> No hay atraso que requiera atención.</Aviso>}
          <dl className="metricas">
            <div><dt>Eventos pendientes</dt><dd>{e.pendientes}</dd></div>
            <div><dt>Antigüedad del más antiguo</dt><dd>{e.pendientes > 0 ? duracion(e.antiguedadMasAntiguaSegundos) : '—'}</dd></div>
            <div><dt>Alarma</dt><dd>{e.alarma ? 'Activa' : 'Inactiva'}</dd></div>
          </dl>
        </>
      )}
      <div className="barra">
        <button className="btn" onClick={() => void cargar()}>Actualizar ahora</button>
        {actualizado && <span className="nota">Última lectura: {actualizado.toLocaleTimeString('es-CO')}</span>}
      </div>
    </>
  );
}
