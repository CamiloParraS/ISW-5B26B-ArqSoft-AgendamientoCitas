import { useState } from 'react';
import { api, guardarToken } from '../api.js';

const DEMO = [
  ['ana', 'Paciente · tiene cita con la Dra. Rojas'],
  ['bruno', 'Paciente'],
  ['dra.rojas', 'Médica tratante de Ana'],
  ['dr.mora', 'Médico sin relación con Ana'],
  ['recepcion', 'Recepción'],
];

export default function Ingreso({ alIngresar }) {
  const [usuario, setUsuario] = useState('ana');
  const [clave, setClave] = useState('demo1234');
  const [error, setError] = useState('');

  async function ingresar(e) {
    e.preventDefault();
    setError('');
    try {
      const r = await api('POST', '/api/identidad/sesiones', { usuario, clave });
      guardarToken(r.token);
      alIngresar(r.usuario);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <main className="contenedor ingreso">
      <h1>Agenda de citas médicas</h1>
      <form className="tarjeta" onSubmit={ingresar}>
        <label>
          Usuario
          <input value={usuario} onChange={(e) => setUsuario(e.target.value)} autoComplete="username" />
        </label>
        <label>
          Clave
          <input type="password" value={clave} onChange={(e) => setClave(e.target.value)} autoComplete="current-password" />
        </label>
        {error && <p className="aviso error">{error}</p>}
        <button type="submit">Ingresar</button>
      </form>
      <section className="tarjeta">
        <h2>Usuarios de demostración</h2>
        <p className="tenue">Clave de todos: demo1234</p>
        <ul className="lista">
          {DEMO.map(([u, d]) => (
            <li key={u}>
              <button className="enlace" onClick={() => setUsuario(u)}>{u}</button> <span className="tenue">{d}</span>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
