"""Un esquema de PostgreSQL por módulo: "notificaciones" pertenece solo a notificaciones."""
from django.db import migrations


class Migration(migrations.Migration):
    initial = True
    dependencies = []
    operations = [
        migrations.RunSQL("CREATE SCHEMA IF NOT EXISTS notificaciones;", reverse_sql="DROP SCHEMA IF EXISTS notificaciones CASCADE;"),
    ]
