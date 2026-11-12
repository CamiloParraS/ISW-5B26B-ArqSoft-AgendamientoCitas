"""Un esquema de PostgreSQL por módulo: "clinico" pertenece solo a atencion_clinica."""
from django.db import migrations


class Migration(migrations.Migration):
    initial = True
    dependencies = []
    operations = [
        migrations.RunSQL("CREATE SCHEMA IF NOT EXISTS clinico;", reverse_sql="DROP SCHEMA IF EXISTS clinico CASCADE;"),
    ]
