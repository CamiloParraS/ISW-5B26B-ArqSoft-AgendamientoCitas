import { useCallback, useEffect, useState } from 'react';
import { api, fechaHora, nuevaClave } from '../api.js';
import { Aviso, Estado, SelectorCupos } from '../componentes.jsx';

// Pantalla de reserva: pide, reprograma y cancela sus citas.
export default function Paciente() {
  const [medicos, setMedicos] = useState([]);
  const [medicoId, setMedicoId] = useState('');
  const [cupos, setCupos] = useState([]);
  const [seleccion, setSeleccion] = useState(null); // { cupo, clave }
  const [reprogramando, setReprogramando] = useState(null);
  const [citas, setCitas] = useState([]);
  const [envios, setEnvios] = useState([]);
  const [aviso, setAviso] = useState(null);
  const [alternativas, setAlternativas] = useState([]);
  const [enviando, setEnviando] = useState(false);

  const cargarCitas = useCallback(async () => {
    const [c, n] = await Promise.all([api('GET', '/api/agenda/citas'), api('GET', '/api/notificaciones/mias')]);
    setCitas(c.citas);
    setEnvios(n.envios);
  }, []);

  const cargarCupos = useCallback(async (id) => {
    if (!id) return;
    setCupos((await api('GET', `/api/agenda/cupos?medico_id=${id}`)).cupos);
  }, []);

  useEffect(() => {
    api('GET', '/api/identidad/medicos').then((r) => {
      setMedicos(r.medicos);
      if (r.medicos[0]) setMedicoId(String(r.medicos[0].id));
    });
    cargarCitas();
    const intervalo = setInterval(cargarCitas, 5000);
    return () => clearInterval(intervalo);
  }, [cargarCitas]);

  useEffect(() => {
    cargarCupos(medicoId);
  }, [medicoId, cargarCupos]);

  function elegir(cupo) {
    // La clave nace al elegir el cupo y se reusa en cualquier reintento o doble clic.
    setSeleccion({ cupo, clave: nuevaClave() });
    setAlternativas([]);
    setAviso(null);
  }

  async function confirmar() {
    if (!seleccion) return;
    setEnviando(true);
    try {
      const ruta = reprogramando ? `/api/agenda/citas/${reprogramando.id}/reprogramar` : '/api/agenda/citas';
      const r = await api('POST', ruta, { cupo_id: seleccion.cupo.id }, { 'Idempotency-Key': seleccion.clave });
      setAviso({ tipo: 'exito', texto: `Cita confirmada: ${fechaHora(r.cita.inicio)} con ${r.cita.medico}.` });
      setSeleccion(null);
      setReprogramando(null);
    } catch (err) {
      if (err.estado === 409) {
        setAviso({ tipo: 'error', texto: 'Ese cupo ya fue tomado. Elija una de estas alternativas:' });
        setAlternativas(err.datos.alternativas || []);
        setSeleccion(null);
      } else {
        setAviso({ tipo: 'error', texto: err.message });
      }
    } finally {
      setEnviando(false);
      cargarCupos(medicoId);
      cargarCitas();
    }
  }

  async function cancelar(cita) {
    try {
      await api('POST', `/api/agenda/citas/${cita.id}/cancelar`);
      setAviso({ tipo: 'exito', texto: 'Cita cancelada.' });
    } catch (err) {
      setAviso({ tipo: 'error', texto: err.message });
    }
    cargarCitas();
    cargarCupos(medicoId);
  }

  function iniciarReprogramacion(cita) {
    setReprogramando(cita);
    setMedicoId(String(cita.medico_id));
    setSeleccion(null);
    setAviso({ tipo: 'info', texto: `Reprogramando la cita del ${fechaHora(cita.inicio)}: elija el nuevo cupo.` });
  }

  return (
    <div className="rejilla">
      <section className="tarjeta">
        <h2>{reprogramando ? 'Reprogramar cita' : 'Reservar cita'}</h2>
        <label>
          Médico
          <select value={medicoId} onChange={(e) => setMedicoId(e.target.value)} disabled={Boolean(reprogramando)}>
            {medicos.map((m) => (
              <option key={m.id} value={m.id}>{m.nombre}</option>
            ))}
          </select>
        </label>
        <Aviso aviso={aviso} />
        {alternativas.length > 0 && (
          <div className="cupos">
            {alternativas.map((c) => (
              <button key={c.id} className="cupo" onClick={() => elegir(c)}>{fechaHora(c.inicio)}</button>
            ))}
          </div>
        )}
        <div className="acciones">
          <button onClick={confirmar} disabled={!seleccion || enviando}>
            {seleccion ? `Confirmar ${fechaHora(seleccion.cupo.inicio)}` : 'Elija un cupo'}
          </button>
          {reprogramando && (
            <button className="secundario" onClick={() => { setReprogramando(null); setAviso(null); }}>
              Dejar como estaba
            </button>
          )}
        </div>
        <SelectorCupos cupos={cupos} seleccionado={seleccion?.cupo.id} alElegir={elegir} />
      </section>

      <div className="columna">
        <section className="tarjeta">
          <h2>Mis citas</h2>
          {!citas.length && <p className="tenue">Aún no tiene citas.</p>}
          <ul className="lista">
            {citas.map((c) => (
              <li key={c.id} className="fila">
                <div>
                  <div>{fechaHora(c.inicio)}</div>
                  <div className="tenue">{c.medico} · cita #{c.id}</div>
                </div>
                <div className="acciones">
                  <Estado valor={c.estado} />
                  {c.estado === 'CONFIRMADA' && (
                    <>
                      <button className="secundario" onClick={() => iniciarReprogramacion(c)}>Reprogramar</button>
                      <button className="peligro" onClick={() => cancelar(c)}>Cancelar</button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
        <section className="tarjeta">
          <h2>Mis notificaciones</h2>
          <p className="tenue">Si el proveedor de correo/SMS está caído, quedan pendientes y el barrido las reintenta.</p>
          <ul className="lista">
            {envios.map((e) => (
              <li key={e.id} className="fila">
                <div>
                  <div>{e.mensaje}</div>
                  <div className="tenue">{e.canal} → {e.destino} · intentos: {e.intentos}</div>
                </div>
                <Estado valor={e.estado} />
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}
