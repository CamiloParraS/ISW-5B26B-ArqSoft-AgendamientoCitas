import { useCallback, useEffect, useState } from 'react';
import { api, fechaHora } from '../api.js';
import { Aviso, Estado } from '../componentes.jsx';

// Pantallas de agenda y atención clínica del médico.
export default function Medico({ usuario }) {
  const [citas, setCitas] = useState([]);
  const [pacientes, setPacientes] = useState([]);
  const [pacienteId, setPacienteId] = useState('');
  const [historia, setHistoria] = useState(null);
  const [avisoAgenda, setAvisoAgenda] = useState(null);
  const [avisoClinico, setAvisoClinico] = useState(null);
  const [form, setForm] = useState({ cita_id: '', diagnostico: '', receta: '' });

  const cargarCitas = useCallback(async () => setCitas((await api('GET', '/api/agenda/citas')).citas), []);

  useEffect(() => {
    cargarCitas();
    api('GET', '/api/identidad/pacientes').then((r) => setPacientes(r.pacientes));
  }, [cargarCitas]);

  async function marcarAsistencia(cita) {
    try {
      await api('POST', `/api/agenda/citas/${cita.id}/asistencia`);
      setAvisoAgenda({ tipo: 'exito', texto: `Asistencia registrada para la cita #${cita.id}.` });
    } catch (err) {
      setAvisoAgenda({ tipo: 'error', texto: err.message });
    }
    cargarCitas();
  }

  function abrirAtencion(cita) {
    setPacienteId(String(cita.paciente_id));
    setForm({ cita_id: String(cita.id), diagnostico: '', receta: '' });
    verHistoria(cita.paciente_id);
  }

  async function verHistoria(id = pacienteId) {
    if (!id) return;
    setHistoria(null);
    setAvisoClinico(null);
    try {
      const r = await api('GET', `/api/clinica/pacientes/${id}/historia`);
      setHistoria(r.registros);
    } catch (err) {
      setAvisoClinico({
        tipo: 'error',
        texto: err.estado === 403 ? 'No autorizado. El intento quedó registrado en la bitácora.' : err.message,
      });
    }
  }

  async function registrar(e) {
    e.preventDefault();
    try {
      await api('POST', `/api/clinica/pacientes/${pacienteId}/atenciones`, { ...form, cita_id: Number(form.cita_id) });
      setAvisoClinico({ tipo: 'exito', texto: 'Atención registrada (cifrada en reposo).' });
      setForm({ ...form, diagnostico: '', receta: '' });
      const r = await api('GET', `/api/clinica/pacientes/${pacienteId}/historia`);
      setHistoria(r.registros);
    } catch (err) {
      setAvisoClinico({
        tipo: 'error',
        texto: err.estado === 403 ? 'No autorizado. El intento quedó registrado en la bitácora.' : err.message,
      });
    }
  }

  const citasDelPaciente = citas.filter((c) => String(c.paciente_id) === pacienteId);

  return (
    <div className="rejilla">
      <section className="tarjeta">
        <h2>Mi agenda</h2>
        <Aviso aviso={avisoAgenda} />
        {!citas.length && <p className="tenue">No tiene citas.</p>}
        <ul className="lista">
          {citas.map((c) => (
            <li key={c.id} className="fila">
              <div>
                <div>{fechaHora(c.inicio)}</div>
                <div className="tenue">{c.paciente} · cita #{c.id}</div>
              </div>
              <div className="acciones">
                <Estado valor={c.estado} />
                {c.estado === 'CONFIRMADA' && (
                  <button className="secundario" onClick={() => marcarAsistencia(c)}>Marcar asistencia</button>
                )}
                <button className="secundario" onClick={() => abrirAtencion(c)}>Atención clínica</button>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section className="tarjeta">
        <h2>Atención clínica</h2>
        <p className="tenue">Solo el médico tratante ve diagnósticos. Cada intento queda en la bitácora.</p>
        <div className="acciones">
          <select value={pacienteId} onChange={(e) => { setPacienteId(e.target.value); setHistoria(null); setAvisoClinico(null); }}>
            <option value="">Elija un paciente…</option>
            {pacientes.map((p) => (
              <option key={p.id} value={p.id}>{p.nombre}</option>
            ))}
          </select>
          <button onClick={() => verHistoria()} disabled={!pacienteId}>Ver historia</button>
        </div>
        <Aviso aviso={avisoClinico} />
        {historia && (
          <>
            {!historia.length && <p className="tenue">Sin registros todavía.</p>}
            <ul className="lista">
              {historia.map((r) => (
                <li key={r.id} className="registro">
                  <div className="tenue">
                    Cita #{r.cita_id} · versión {r.version} · {r.medico} · {fechaHora(r.registrado)}
                  </div>
                  <div><strong>Diagnóstico:</strong> {r.diagnostico}</div>
                  <div><strong>Receta:</strong> {r.receta || '—'}</div>
                </li>
              ))}
            </ul>
            <form onSubmit={registrar} className="formulario">
              <h3>Registrar atención</h3>
              <label>
                Cita
                <select value={form.cita_id} onChange={(e) => setForm({ ...form, cita_id: e.target.value })} required>
                  <option value="">Elija la cita…</option>
                  {citasDelPaciente.map((c) => (
                    <option key={c.id} value={c.id}>#{c.id} · {fechaHora(c.inicio)}</option>
                  ))}
                </select>
              </label>
              <label>
                Diagnóstico
                <textarea value={form.diagnostico} onChange={(e) => setForm({ ...form, diagnostico: e.target.value })} required />
              </label>
              <label>
                Receta
                <textarea value={form.receta} onChange={(e) => setForm({ ...form, receta: e.target.value })} />
              </label>
              <button type="submit">Guardar (nueva versión)</button>
            </form>
          </>
        )}
      </section>
    </div>
  );
}
