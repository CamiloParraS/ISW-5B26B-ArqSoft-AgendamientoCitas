export interface Especialidad { id: string; nombre: string }
export interface Cupo {
  id: string; medicoId: string; medicoNombre: string; especialidad: string;
  sedeId: string; sedeNombre: string; inicio: string; fin: string;
}
export interface Retencion { cupoId: string; retenidoHasta: string }
export interface Cita {
  id: string; estado: 'CONFIRMADA' | 'CANCELADA' | 'ATENDIDA' | string;
  inicio: string; fin: string; medicoNombre: string; especialidad: string; sedeNombre: string;
  conPenalidad?: boolean;
  horasMinimasCancelacion?: number; cancelacionConPenalidad?: boolean;
}
export interface ResultadoCancelacion { id: string; estado: string; conPenalidad: boolean }
export interface Institucion { codigo: string; nombre: string }
export interface Consentimiento {
  id: string; institucionCodigo: string; institucionNombre?: string;
  otorgadoEn?: string; vigenteHasta?: string; revocadoEn?: string | null;
}
export interface Bitacora {
  id?: number | string; ocurridoEn: string; institucionCodigo?: string; institucionNombre?: string;
  resultado?: string; usuarioNombre?: string;
}
