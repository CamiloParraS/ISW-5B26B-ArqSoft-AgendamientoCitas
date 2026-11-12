"""Un esquema de PostgreSQL por módulo: "identidad" pertenece solo a identidad."""
from django.db import migrations


class Migration(migrations.Migration):
    initial = True
    dependencies = []
    operations = [
        migrations.RunSQL("CREATE SCHEMA IF NOT EXISTS identidad;", reverse_sql="DROP SCHEMA IF EXISTS identidad CASCADE;"),
    ]
