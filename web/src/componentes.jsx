import { diaClave, fecha, hora } from './api.js';

export function Aviso({ aviso }) {
  if (!aviso) return null;
  return <p className={`aviso ${aviso.tipo}`}>{aviso.texto}</p>;
}

export function Estado({ valor }) {
  return <span className={`estado estado-${valor.toLowerCase()}`}>{valor}</span>;
}

// Cupos libres agrupados por día; el seleccionado queda resaltado.
export function SelectorCupos({ cupos, seleccionado, alElegir }) {
  if (!cupos.length) return <p className="tenue">No hay cupos libres.</p>;
  const porDia = {};
  for (const c of cupos) (porDia[diaClave(c.inicio)] ||= []).push(c);
  return Object.values(porDia).map((lista) => (
    <div key={lista[0].inicio} className="dia">
      <h3>{fecha(lista[0].inicio)}</h3>
      <div className="cupos">
        {lista.map((c) => (
          <button
            key={c.id}
            className={`cupo ${seleccionado === c.id ? 'activo' : ''}`}
            onClick={() => alElegir(c)}
          >
            {hora(c.inicio)}
          </button>
        ))}
      </div>
    </div>
  ));
}
