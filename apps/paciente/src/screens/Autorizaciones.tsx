import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Consentimiento, Institucion } from '../types';
import { fechaCorta } from '../format';
import { mensajeDeError } from '../mensajes';
import { Aviso, Titulo } from '../components/comunes';

const vigente = (c: Consentimiento) =>
  !c.revocadoEn && (!c.vigenteHasta || Date.parse(c.vigenteHasta) > Date.now());

export function Autorizaciones() {
  const [ips, setIps] = useState<Institucion[] | null>(null);
  const [cons, setCons] = useState<Consentimiento[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState<string | null>(null);
  const [quitando, setQuitando] = useState<Institucion | null>(null);

  const cargar = useCallback(async () => {
    try {
      const [i, c] = await Promise.all([api.instituciones(), api.consentimientos()]);
      setIps(i);
      setCons(c);
    } catch (e) {
      setIps((x) => x ?? []);
      setError(mensajeDeError(e));
    }
  }, []);
  useEffect(() => { void cargar(); }, [cargar]);

  const vigenteDe = (codigo: string) => cons.find((c) => c.institucionCodigo === codigo && vigente(c));

  const autorizar = async (i: Institucion) => {
    setOcupado(i.codigo);
    setError(null);
    try {
      await api.autorizar(i.codigo);
      await cargar();
    } catch (e) {
      setError(mensajeDeError(e));
    } finally {
      setOcupado(null);
    }
  };

  const quitar = async () => {
    const c = quitando && vigenteDe(quitando.codigo);
    if (!quitando || !c) { setQuitando(null); return; }
    setOcupado(quitando.codigo);
    setError(null);
    try {
      await api.revocar(c.id);
      setQuitando(null);
      await cargar();
    } catch (e) {
      setError(mensajeDeError(e));
    } finally {
      setOcupado(null);
    }
  };

  if (quitando) {
    return (
      <section>
        <Titulo>¿Quitar la autorización?</Titulo>
        <p>
          <strong>{quitando.nombre}</strong> ya no podrá consultar la fecha, hora, sede y médico de sus citas.
        </p>
        {error && <Aviso tipo="error">{error}</Aviso>}
        <button className="btn primario enorme" onClick={() => setQuitando(null)} disabled={ocupado !== null}>
          No, dejarla como está
        </button>
        <button className="btn peligro" onClick={quitar} disabled={ocupado !== null}>
          Sí, quitar autorización
        </button>
      </section>
    );
  }

  return (
    <section>
      <Titulo>Autorizaciones</Titulo>
      <p>
        Usted decide qué instituciones aliadas pueden consultar la fecha, hora, sede y médico de sus citas, por ejemplo para
        avisarle por teléfono. Puede quitar el permiso cuando quiera.
      </p>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {!ips && <p role="status">Cargando instituciones…</p>}
      <ul className="lista">
        {ips?.map((i) => {
          const c = vigenteDe(i.codigo);
          return (
            <li key={i.codigo} className="tarjeta">
              <p className="grande">{i.nombre}</p>
              {c ? (
                <>
                  <p className="estado ok">
                    Autorizada{c.vigenteHasta ? ` hasta el ${fechaCorta(c.vigenteHasta)}` : ''}
                  </p>
                  <button className="btn peligro-suave" onClick={() => setQuitando(i)} disabled={ocupado !== null}>
                    Quitar autorización
                  </button>
                </>
              ) : (
                <>
                  <p className="estado">Sin autorización</p>
                  <button
                    className="btn primario"
                    onClick={() => autorizar(i)}
                    disabled={ocupado !== null}
                    aria-busy={ocupado === i.codigo}
                  >
                    {ocupado === i.codigo ? 'Autorizando…' : 'Autorizar por 1 año'}
                  </button>
                </>
              )}
            </li>
          );
        })}
      </ul>
      <Link to="/" className="btn texto">Volver al inicio</Link>
    </section>
  );
}
