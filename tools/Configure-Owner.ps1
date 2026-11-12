param([switch]$Replace)

$ErrorActionPreference = 'Stop'
$config = Join-Path (Split-Path -Parent $PSScriptRoot) 'owner.properties'
if ((Test-Path -LiteralPath $config) -and -not $Replace) {
    throw 'Ya existe una cuenta configurada. Usa -Replace para cambiarla.'
}

$username = (Read-Host 'Nombre de usuario administrador').Trim()
if ($username -cnotmatch '\A[A-Za-z0-9_.]{4,20}\z') {
    throw 'El usuario debe tener 4 a 20 caracteres: letras, numeros, punto o guion bajo.'
}

$password = Read-Host 'Clave aleatoria (24 a 64 caracteres ASCII imprimibles, sin espacios)' -AsSecureString
$confirmation = Read-Host 'Confirma la clave' -AsSecureString
try {
    $plain = [System.Net.NetworkCredential]::new('', $password).Password
    if ($plain -cnotmatch '\A[!-~]{24,64}\z') {
        throw 'La clave debe tener 24 a 64 caracteres ASCII imprimibles, sin espacios.'
    }
    if ($plain -cne [System.Net.NetworkCredential]::new('', $confirmation).Password) {
        throw 'Las claves no coinciden.'
    }

    $iterations = 600000
    $salt = New-Object byte[] 16
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($salt)
    } finally {
        $random.Dispose()
    }
    $pbkdf2 = [System.Security.Cryptography.Rfc2898DeriveBytes]::new(
        $plain, $salt, $iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try {
        $saltHex = [BitConverter]::ToString($salt).Replace('-', '').ToLowerInvariant()
        $hashHex = [BitConverter]::ToString($pbkdf2.GetBytes(32)).Replace('-', '').ToLowerInvariant()
        @("username=$username", "passwordHash=${iterations}:${saltHex}:${hashHex}") |
            Set-Content -LiteralPath $config -Encoding Ascii
    } finally {
        $pbkdf2.Dispose()
    }
} finally {
    $plain = $null
    $password.Dispose()
    $confirmation.Dispose()
}

Write-Host 'Cuenta de administrador configurada. Vuelve a compilar la APK.'
