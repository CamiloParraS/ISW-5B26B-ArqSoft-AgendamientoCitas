"""Un esquema de PostgreSQL por módulo: "agenda" pertenece solo a agenda."""
from django.db import migrations


class Migration(migrations.Migration):
    initial = True
    dependencies = []
    operations = [
        migrations.RunSQL("CREATE SCHEMA IF NOT EXISTS agenda;", reverse_sql="DROP SCHEMA IF EXISTS agenda CASCADE;"),
    ]
