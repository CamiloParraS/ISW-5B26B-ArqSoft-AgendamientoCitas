import { useEffect, useState } from 'react';
import { api, guardarToken, hayToken } from './api.js';
import Ingreso from './paginas/Ingreso.jsx';
import Paciente from './paginas/Paciente.jsx';
import Medico from './paginas/Medico.jsx';
import Recepcion from './paginas/Recepcion.jsx';

const NOMBRE_ROL = { PACIENTE: 'Paciente', MEDICO: 'Médico', RECEPCION: 'Recepción' };

export default function App() {
  const [usuario, setUsuario] = useState(null);
  const [cargando, setCargando] = useState(hayToken());

  useEffect(() => {
    if (!hayToken()) return;
    api('GET', '/api/identidad/yo')
      .then(setUsuario)
      .catch(() => guardarToken(null))
      .finally(() => setCargando(false));
  }, []);

  async function salir() {
    try {
      await api('DELETE', '/api/identidad/sesiones');
    } catch {
      /* la sesión ya no existe */
    }
    guardarToken(null);
    setUsuario(null);
  }

  if (cargando) return <main className="contenedor">Cargando…</main>;
  if (!usuario) return <Ingreso alIngresar={setUsuario} />;

  return (
    <>
      <header className="barra">
        <div className="contenedor barra-interior">
          <strong>Agenda de citas médicas</strong>
          <span className="usuario">
            {usuario.nombre} · {NOMBRE_ROL[usuario.rol]}
            <button className="secundario" onClick={salir}>Salir</button>
          </span>
        </div>
      </header>
      <main className="contenedor">
        {usuario.rol === 'PACIENTE' && <Paciente />}
        {usuario.rol === 'MEDICO' && <Medico usuario={usuario} />}
        {usuario.rol === 'RECEPCION' && <Recepcion />}
      </main>
    </>
  );
}
