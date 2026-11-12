"""Bitácora de solo inserción (táctica "Auditar") y registros clínicos que no se borran, se versionan (R-3).

La garantía la da la base: cualquier UPDATE o DELETE sobre estas tablas falla, venga de donde venga.
En producción, además, el rol de la aplicación no debe tener privilegio TRUNCATE sobre el esquema clinico.
"""
from django.db import migrations

SQL = """
CREATE OR REPLACE FUNCTION clinico.rechazar_modificacion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La tabla %.% es de solo inserción: % no permitido', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bitacora_solo_insercion
    BEFORE UPDATE OR DELETE ON clinico.bitacora_acceso
    FOR EACH ROW EXECUTE FUNCTION clinico.rechazar_modificacion();

CREATE TRIGGER registro_clinico_solo_insercion
    BEFORE UPDATE OR DELETE ON clinico.registro_clinico
    FOR EACH ROW EXECUTE FUNCTION clinico.rechazar_modificacion();
"""

SQL_REVERSO = """
DROP TRIGGER IF EXISTS bitacora_solo_insercion ON clinico.bitacora_acceso;
DROP TRIGGER IF EXISTS registro_clinico_solo_insercion ON clinico.registro_clinico;
DROP FUNCTION IF EXISTS clinico.rechazar_modificacion();
"""


class Migration(migrations.Migration):
    dependencies = [("atencion_clinica", "0002_initial")]
    operations = [migrations.RunSQL(SQL, reverse_sql=SQL_REVERSO)]
