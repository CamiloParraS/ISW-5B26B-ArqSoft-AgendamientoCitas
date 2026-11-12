import { useCallback, useEffect, useState } from 'react';
import { api, fechaHora } from '../api.js';
import { Aviso, Estado } from '../componentes.jsx';

const manana = () => {
  const d = new Date(Date.now() + 86400000);
  return d.toLocaleDateString('en-CA', { timeZone: 'America/Bogota' });
};

// Recepción: define horarios y abre cupos. No tiene pantalla clínica (S-2).
export default function Recepcion() {
  const [medicos, setMedicos] = useState([]);
  const [horario, setHorario] = useState({ medico_id: '', fecha: manana(), hora_inicio: '14:00', hora_fin: '17:00', duracion_minutos: 20 });
  const [filtroMedico, setFiltroMedico] = useState('');
  const [citas, setCitas] = useState([]);
  const [mes, setMes] = useState(new Date().toLocaleDateString('en-CA', { timeZone: 'America/Bogota' }).slice(0, 7));
  const [reporte, setReporte] = useState(null);
  const [salud, setSalud] = useState(null);
  const [aviso, setAviso] = useState(null);

  const cargarCitas = useCallback(async () => {
    const q = filtroMedico ? `?medico_id=${filtroMedico}` : '';
    setCitas((await api('GET', `/api/agenda/citas${q}`)).citas);
  }, [filtroMedico]);

  useEffect(() => {
    api('GET', '/api/identidad/medicos').then((r) => {
      setMedicos(r.medicos);
      if (r.medicos[0]) setHorario((h) => ({ ...h, medico_id: String(r.medicos[0].id) }));
    });
    const leerSalud = () => fetch('/salud').then((r) => r.json()).then(setSalud).catch(() => setSalud(null));
    leerSalud();
    const intervalo = setInterval(leerSalud, 10000);
    return () => clearInterval(intervalo);
  }, []);

  useEffect(() => {
    cargarCitas();
  }, [cargarCitas]);

  async function abrirCupos(e) {
    e.preventDefault();
    try {
      const r = await api('POST', '/api/agenda/horarios', { ...horario, medico_id: Number(horario.medico_id), duracion_minutos: Number(horario.duracion_minutos) });
      setAviso({ tipo: 'exito', texto: `Cupos abiertos: ${r.creados} nuevos (${r.existentes} ya existían).` });
    } catch (err) {
      setAviso({ tipo: 'error', texto: err.message });
    }
  }

  async function cancelar(cita) {
    try {
      await api('POST', `/api/agenda/citas/${cita.id}/cancelar`);
    } catch (err) {
      setAviso({ tipo: 'error', texto: err.message });
    }
    cargarCitas();
  }

  async function verReporte() {
    const inicio = performance.now();
    const r = await api('GET', `/api/agenda/reportes/mensual?mes=${mes}`);
    setReporte({ ...r, segundos: ((performance.now() - inicio) / 1000).toFixed(2) });
  }

  return (
    <>
      {salud && (
        <p className={`aviso ${salud.barrido_atrasado ? 'error' : 'info'}`}>
          {salud.barrido_atrasado
            ? `Alerta: la tarea programada no corre desde hace más de 15 min (último barrido: ${salud.ultimo_barrido ? fechaHora(salud.ultimo_barrido) : 'nunca'}).`
            : `Sistema en línea · último barrido ${fechaHora(salud.ultimo_barrido)}.`}
        </p>
      )}
      <div className="rejilla">
        <section className="tarjeta">
          <h2>Definir horario y abrir cupos</h2>
          <form onSubmit={abrirCupos} className="formulario">
            <label>
              Médico
              <select value={horario.medico_id} onChange={(e) => setHorario({ ...horario, medico_id: e.target.value })}>
                {medicos.map((m) => (
                  <option key={m.id} value={m.id}>{m.nombre}</option>
                ))}
              </select>
            </label>
            <label>
              Fecha
              <input type="date" value={horario.fecha} onChange={(e) => setHorario({ ...horario, fecha: e.target.value })} />
            </label>
            <div className="dos">
              <label>
                Desde
                <input type="time" value={horario.hora_inicio} onChange={(e) => setHorario({ ...horario, hora_inicio: e.target.value })} />
              </label>
              <label>
                Hasta
                <input type="time" value={horario.hora_fin} onChange={(e) => setHorario({ ...horario, hora_fin: e.target.value })} />
              </label>
            </div>
            <label>
              Duración del cupo (min)
              <input type="number" min="5" max="240" value={horario.duracion_minutos} onChange={(e) => setHorario({ ...horario, duracion_minutos: e.target.value })} />
            </label>
            <button type="submit">Abrir cupos</button>
          </form>
          <Aviso aviso={aviso} />

          <h2>Reporte mensual</h2>
          <div className="acciones">
            <input type="month" value={mes} onChange={(e) => setMes(e.target.value)} />
            <button className="secundario" onClick={verReporte}>Generar</button>
          </div>
          {reporte && (
            <>
              <p className="tenue">Generado en {reporte.segundos} s</p>
              <div className="tabla">
                <table>
                  <thead>
                    <tr><th>Médico</th><th>Cupos</th><th>Confirmadas</th><th>Atendidas</th><th>Inasistencias</th><th>Canceladas</th></tr>
                  </thead>
                  <tbody>
                    {reporte.filas.map((f) => (
                      <tr key={f.medico_id}>
                        <td>{f.medico}</td><td>{f.cupos}</td><td>{f.CONFIRMADA}</td><td>{f.ATENDIDA}</td><td>{f.INASISTENCIA}</td><td>{f.CANCELADA}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </section>

        <section className="tarjeta">
          <h2>Agenda</h2>
          <select value={filtroMedico} onChange={(e) => setFiltroMedico(e.target.value)}>
            <option value="">Todos los médicos</option>
            {medicos.map((m) => (
              <option key={m.id} value={m.id}>{m.nombre}</option>
            ))}
          </select>
          {!citas.length && <p className="tenue">Sin citas.</p>}
          <ul className="lista">
            {citas.map((c) => (
              <li key={c.id} className="fila">
                <div>
                  <div>{fechaHora(c.inicio)}</div>
                  <div className="tenue">{c.paciente} con {c.medico} · cita #{c.id}</div>
                </div>
                <div className="acciones">
                  <Estado valor={c.estado} />
                  {c.estado === 'CONFIRMADA' && <button className="peligro" onClick={() => cancelar(c)}>Cancelar</button>}
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </>
  );
}
