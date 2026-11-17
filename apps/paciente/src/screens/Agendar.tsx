import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import type { Cita, Cupo, Especialidad } from '../types';
import { fechaHora, fechaLarga, hoyIso, hora, mayuscula } from '../format';
import { mensajeDeError } from '../mensajes';
import { Aviso, CuentaRegresiva, Pasos, Titulo } from '../components/comunes';

type Fase = 'especialidad' | 'cupo' | 'confirmar' | 'listo';
const POR_PAGINA = 10;

/** Clave de idempotencia: una por intento de confirmación, viva en sessionStorage hasta el éxito. */
function claveDe(operacion: string, cupoId: string): string {
  const k = `medicita.idem.${operacion}.${cupoId}`;
  try {
    let v = sessionStorage.getItem(k);
    if (!v) {
      v = crypto.randomUUID();
      sessionStorage.setItem(k, v);
    }
    return v;
  } catch {
    return crypto.randomUUID();
  }
}
function olvidarClave(operacion: string, cupoId: string) {
  try { sessionStorage.removeItem(`medicita.idem.${operacion}.${cupoId}`); } catch { /* sin storage */ }
}

function OpcionCupo({ cupo, alElegir, deshabilitado }: { cupo: Cupo; alElegir: (c: Cupo) => void; deshabilitado: boolean }) {
  return (
    <li>
      <button className="opcion" onClick={() => alElegir(cupo)} disabled={deshabilitado}>
        <span className="opcion-hora">{mayuscula(fechaLarga(cupo.inicio))} · {hora(cupo.inicio)}</span>
        <span className="opcion-detalle">{cupo.medicoNombre} · {cupo.sedeNombre}</span>
      </button>
    </li>
  );
}

export function Agendar() {
  const { id: citaId } = useParams();
  const reprogramando = Boolean(citaId);
  const operacion = reprogramando ? `reprogramar.${citaId}` : 'agendar';

  const [fase, setFase] = useState<Fase>(reprogramando ? 'cupo' : 'especialidad');
  const [especialidades, setEspecialidades] = useState<Especialidad[] | null>(null);
  const [esp, setEsp] = useState<Especialidad | null>(null);
  const [original, setOriginal] = useState<Cita | null>(null);
  const [cupos, setCupos] = useState<Cupo[] | null>(null);
  const [verMas, setVerMas] = useState(POR_PAGINA);
  const [fecha, setFecha] = useState(hoyIso());
  const [fechaBuscada, setFechaBuscada] = useState<string | null>(null);
  const [alternativas, setAlternativas] = useState<Cupo[]>([]);
  const [sel, setSel] = useState<Cupo | null>(null);
  const [hasta, setHasta] = useState(0);
  const [vencido, setVencido] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cita, setCita] = useState<Cita | null>(null);

  const cargarProximos = useCallback((e: Especialidad) => {
    setCupos(null);
    setFechaBuscada(null);
    setVerMas(POR_PAGINA);
    api.cuposProximos(e.id)
      .then(setCupos)
      .catch((x) => { setCupos([]); setError(mensajeDeError(x)); });
  }, []);

  // Carga inicial: lista de especialidades (y, al reprogramar, la cita original)
  useEffect(() => {
    let vivo = true;
    (async () => {
      try {
        const lista = await api.especialidades();
        if (!vivo) return;
        setEspecialidades(lista);
        if (citaId) {
          const mias = await api.misCitas();
          const c = mias.find((x) => x.id === citaId && x.estado === 'CONFIRMADA');
          const e = c && lista.find((x) => x.nombre === c.especialidad);
          if (!vivo) return;
          if (!c || !e) {
            setError('No encontramos esa cita para cambiarla. Vuelva a Mis citas.');
            return;
          }
          setOriginal(c);
          setEsp(e);
          cargarProximos(e);
        }
      } catch (x) {
        if (vivo) setError(mensajeDeError(x));
      }
    })();
    return () => { vivo = false; };
  }, [citaId, cargarProximos]);

  const elegirEspecialidad = (e: Especialidad) => {
    setError(null);
    setAlternativas([]);
    setEsp(e);
    setFase('cupo');
    cargarProximos(e);
  };

  const buscarDia = async () => {
    if (!esp) return;
    setError(null);
    setOcupado(true);
    try {
      const lista = await api.cuposDelDia(esp.id, fecha);
      setCupos(lista);
      setFechaBuscada(fecha);
      setVerMas(POR_PAGINA);
      setAlternativas([]);
    } catch (x) {
      setError(mensajeDeError(x));
    } finally {
      setOcupado(false);
    }
  };

  const retener = async (c: Cupo) => {
    if (ocupado) return;
    setError(null);
    setOcupado(true);
    try {
      const r = await api.retener(c.id);
      const limite = Date.parse(r.retenidoHasta);
      setHasta(Number.isNaN(limite) ? Date.now() + 5 * 60_000 : limite);
      setVencido(false);
      setSel(c);
      setAlternativas([]);
      setFase('confirmar');
    } catch (x) {
      if (x instanceof ApiError && x.status === 409 && x.codigo === 'CUPO_NO_DISPONIBLE') {
        setAlternativas(x.alternativas);
        setError('Ese horario acaba de ser tomado por otra persona. Elija una de estas horas libres:');
        if (esp) cargarProximos(esp);
      } else {
        setError(mensajeDeError(x));
      }
    } finally {
      setOcupado(false);
    }
  };

  const confirmar = async () => {
    if (!sel || ocupado) return;
    setError(null);
    setOcupado(true);
    const clave = claveDe(operacion, sel.id);
    try {
      const r = reprogramando && citaId
        ? await api.reprogramar(citaId, sel.id, clave)
        : await api.confirmar(sel.id, clave);
      olvidarClave(operacion, sel.id);
      setCita(r);
      setFase('listo');
    } catch (x) {
      if (x instanceof ApiError && x.status === 409 && x.codigo === 'CUPO_NO_DISPONIBLE') {
        olvidarClave(operacion, sel.id);
        setAlternativas(x.alternativas);
        setError('Su reserva de 5 minutos terminó o el horario ya no está libre. Elija una de estas horas:');
        setSel(null);
        setFase('cupo');
        if (esp) cargarProximos(esp);
      } else {
        // Se conserva la clave: tocar "Confirmar" otra vez no duplica la cita (otros 409 muestran el mensaje de la API).
        setError(mensajeDeError(x));
      }
    } finally {
      setOcupado(false);
    }
  };

  const alVencer = useCallback(() => setVencido(true), []);
  const volverACupos = () => {
    setError(null);
    setSel(null);
    setFase('cupo');
    if (esp) cargarProximos(esp);
  };

  const paso: 1 | 2 | 3 = fase === 'especialidad' ? 1 : fase === 'cupo' ? 2 : 3;

  const mensajeError = error && <Aviso tipo={alternativas.length ? 'alerta' : 'error'}>{error}</Aviso>;

  return (
    <>
      <Pasos actual={paso} />

      {fase === 'especialidad' && (
        <section>
          <Titulo clave={fase}>¿Con qué especialidad desea su cita?</Titulo>
          {mensajeError}
          {!especialidades && !error && <p role="status">Cargando especialidades…</p>}
          <ul className="lista">
            {especialidades?.map((e) => (
              <li key={e.id}>
                <button className="opcion" onClick={() => elegirEspecialidad(e)}>
                  <span className="opcion-hora">{e.nombre}</span>
                </button>
              </li>
            ))}
          </ul>
          <Link to="/" className="btn texto">Volver al inicio</Link>
        </section>
      )}

      {fase === 'cupo' && (
        <section>
          <Titulo clave={fase + (fechaBuscada ?? '')}>
            {reprogramando ? 'Elija el nuevo horario' : 'Elija su horario'}
          </Titulo>
          {original && (
            <p className="suave">
              Cita actual: {mayuscula(fechaHora(original.inicio))}. Se cancelará sin penalidad cuando confirme la nueva.
            </p>
          )}
          {esp && <p className="suave">Especialidad: <strong>{esp.nombre}</strong></p>}
          {mensajeError}

          {alternativas.length > 0 && (
            <ul className="lista alternativas" aria-label="Horas libres cercanas">
              {alternativas.map((c) => (
                <OpcionCupo key={c.id} cupo={c} alElegir={retener} deshabilitado={ocupado} />
              ))}
            </ul>
          )}

          {alternativas.length === 0 && !fechaBuscada && (
            <button
              className="btn primario enorme"
              disabled={ocupado || !cupos || cupos.length === 0}
              onClick={() => cupos && retener(cupos[0])}
            >
              Primer cupo disponible
              {cupos?.[0] && <small>{mayuscula(fechaLarga(cupos[0].inicio))} · {hora(cupos[0].inicio)}</small>}
            </button>
          )}

          {!cupos && !error && <p role="status">Buscando horarios libres…</p>}
          {cupos && cupos.length === 0 && (
            <Aviso tipo="info">
              {fechaBuscada
                ? 'No hay horarios libres ese día. Pruebe con otra fecha.'
                : 'No encontramos horarios libres por ahora. Pruebe con otra fecha.'}
            </Aviso>
          )}

          {cupos && cupos.length > 0 && alternativas.length === 0 && (
            <>
              <h2 className="subtitulo">
                {fechaBuscada ? `Horas libres el ${fechaLarga(`${fechaBuscada}T12:00:00-05:00`)}` : 'O elija otra hora cercana'}
              </h2>
              <ul className="lista">
                {cupos.slice(0, verMas).map((c) => (
                  <OpcionCupo key={c.id} cupo={c} alElegir={retener} deshabilitado={ocupado} />
                ))}
              </ul>
              {cupos.length > verMas && (
                <button className="btn secundario" onClick={() => setVerMas((n) => n + POR_PAGINA)}>
                  Ver más horas
                </button>
              )}
            </>
          )}

          <div className="bloque">
            <label htmlFor="otro-dia">¿Prefiere otro día?</label>
            <div className="fila">
              <input id="otro-dia" type="date" min={hoyIso()} value={fecha} onChange={(e) => setFecha(e.target.value)} />
              <button className="btn secundario" onClick={buscarDia} disabled={ocupado || !fecha}>
                Ver horas de ese día
              </button>
            </div>
            {fechaBuscada && esp && (
              <button className="btn texto" onClick={() => cargarProximos(esp)}>Volver a las horas más cercanas</button>
            )}
          </div>

          {reprogramando ? (
            <Link to="/citas" className="btn texto">No cambiar mi cita</Link>
          ) : (
            <button className="btn texto" onClick={() => { setError(null); setAlternativas([]); setFase('especialidad'); }}>
              Cambiar de especialidad
            </button>
          )}
        </section>
      )}

      {fase === 'confirmar' && sel && (
        <section>
          <Titulo clave={fase}>{reprogramando ? 'Confirme el cambio' : 'Confirme su cita'}</Titulo>
          <div className="tarjeta resumen">
            <p className="grande">{mayuscula(fechaLarga(sel.inicio))}</p>
            <p className="grande">{hora(sel.inicio)}</p>
            <p>{sel.especialidad} · {sel.medicoNombre}</p>
            <p>{sel.sedeNombre}</p>
          </div>
          <CuentaRegresiva hasta={hasta} alVencer={alVencer} />
          {mensajeError}
          {vencido ? (
            <>
              <Aviso tipo="alerta">El tiempo terminó y el cupo fue liberado. No pasa nada: puede elegir otro horario.</Aviso>
              <button className="btn primario enorme" onClick={volverACupos}>Buscar otro horario</button>
            </>
          ) : (
            <>
              <button className="btn primario enorme" onClick={confirmar} disabled={ocupado} aria-busy={ocupado}>
                {ocupado ? 'Confirmando…' : reprogramando ? 'Confirmar el cambio' : 'Confirmar mi cita'}
              </button>
              <button className="btn texto" onClick={volverACupos} disabled={ocupado}>Elegir otro horario</button>
            </>
          )}
        </section>
      )}

      {fase === 'listo' && cita && (
        <section>
          <Titulo clave={fase}>{reprogramando ? '¡Su cita fue cambiada!' : '¡Su cita quedó confirmada!'}</Titulo>
          <Aviso tipo="ok">Le enviaremos un recordatorio. No necesita hacer nada más.</Aviso>
          <div className="tarjeta resumen">
            <p className="grande">{mayuscula(fechaLarga(cita.inicio))}</p>
            <p className="grande">{hora(cita.inicio)}</p>
            <p>{cita.especialidad} · {cita.medicoNombre}</p>
            <p>{cita.sedeNombre}</p>
          </div>
          <Link to="/citas" className="btn primario enorme">Ver mis citas</Link>
          <Link to="/" className="btn texto">Volver al inicio</Link>
        </section>
      )}

      {reprogramando && error && !esp && (
        <Link to="/citas" className="btn secundario">Volver a Mis citas</Link>
      )}
    </>
  );
}
