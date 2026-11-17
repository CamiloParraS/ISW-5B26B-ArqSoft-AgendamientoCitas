# Instala (o quita con -Quitar) la autoridad local de MediCita en el almacén de confianza
# del USUARIO actual de Windows, para que Chrome y Edge abran https://localhost sin advertencia.
# La autoridad tiene nameConstraints: sólo es válida para localhost, 127.0.0.1 y ::1.
# Requiere haber levantado el gateway una vez (genera infra\gateway\certs\medicita-ca.crt).
# Uso:  powershell -ExecutionPolicy Bypass -File infra\gateway\confiar-certificado.ps1 [-Quitar]
param([switch]$Quitar)
$ErrorActionPreference = 'Stop'
$ca = Join-Path $PSScriptRoot 'certs\medicita-ca.crt'

if ($Quitar) {
    Get-ChildItem Cert:\CurrentUser\Root |
        Where-Object { $_.Subject -like '*MediCita CA local*' } |
        ForEach-Object { Remove-Item $_.PSPath; Write-Host "Quitada: $($_.Thumbprint)" }
    return
}
if (-not (Test-Path $ca)) {
    throw "No existe $ca. Levante el sistema primero: docker compose up -d --build"
}
# Quita versiones anteriores (p. ej. si se borró la carpeta certs y se regeneró la autoridad)
Get-ChildItem Cert:\CurrentUser\Root |
    Where-Object { $_.Subject -like '*MediCita CA local*' } |
    ForEach-Object { Remove-Item $_.PSPath }
# Windows pedirá confirmación en un cuadro de diálogo: acéptelo.
Import-Certificate -FilePath $ca -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
Write-Host "Autoridad MediCita instalada. Cierre y abra el navegador y entre a https://localhost"
