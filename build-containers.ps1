<#
.SYNOPSIS
  Build và chạy 5 container của dự án TOTP 2FA trên Windows 10/11 (Docker Desktop).

.DESCRIPTION
  Bản Windows của build-containers.sh. Đặt cạnh 3 thư mục: app_mobile, be-web-admin, fe_web_admin.
  Chạy không tham số để hiện menu.

  Các lệnh (Action):
    init      Tạo be-web-admin\.env với mật khẩu và khóa ngẫu nhiên (lần đầu)
    up        Build và chạy cả 5 container
    build     Chỉ build image, không chạy
    down      Dừng và xóa container (giữ dữ liệu MariaDB, Redis)
    clean     Như down nhưng XÓA luôn volume dữ liệu và mạng
    restart   down rồi up
    ps        Xem trạng thái container
    logs      Xem log (mặc định spring-app; đổi bằng tham số thứ hai)
    apk       Build file app-debug.apk cho Android emulator
    doctor    Kiểm tra Docker, file .env, cổng, xuống dòng
    fix-eol   Đổi Dockerfile từ CRLF sang LF (lỗi hay gặp khi clone trên Windows)

.PARAMETER Action
  Một trong các lệnh ở trên. Bỏ trống để mở menu.

.PARAMETER Service
  Tên container cho lệnh logs: spring-app, vue-web, flutter-web, mariadb, redis.

.PARAMETER PublicHost
  Địa chỉ mà TRÌNH DUYỆT dùng để mở web (được nhúng vào bản build Vue và Flutter).
  Mặc định localhost. Mở từ máy khác trong LAN: -PublicHost 192.168.1.10

.PARAMETER Open
  Tự mở trình duyệt vào trang Vue sau khi up.

.PARAMETER Force
  init: ghi đè .env đã có (file cũ được sao lưu thành .env.bak).
  clean: bỏ qua bước hỏi xác nhận.

.EXAMPLE
  .\build-containers.ps1 init
  .\build-containers.ps1 up -Open
  .\build-containers.ps1 up -PublicHost 192.168.1.10
  .\build-containers.ps1 logs vue-web
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'build', 'down', 'clean', 'restart', 'ps', 'status', 'logs', 'apk', 'init', 'doctor', 'fix-eol', 'help')]
    [string]$Action,

    [Parameter(Position = 1)]
    [string]$Service = 'spring-app',

    [string]$PublicHost,
    [switch]$Open,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$global:LASTEXITCODE = 0
$script:ExitCode = 0

# Console Windows 10 mặc định không phải UTF-8: ép UTF-8 để tiếng Việt không bị vỡ chữ.
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

# ---------------------------------------------------------------------------
# Cấu hình
# ---------------------------------------------------------------------------
$Root       = $PSScriptRoot
$Network    = 'totp-net'
$BeDir      = Join-Path $Root 'be-web-admin'
$FeDir      = Join-Path $Root 'fe_web_admin'
$AppDir     = Join-Path $Root 'app_mobile'
$BeCompose  = Join-Path $BeDir 'docker-compose.yml'
$FeCompose  = Join-Path $FeDir 'docker-compose.yml'
$AppCompose = Join-Path $AppDir 'docker-compose.yml'
$BeEnvFile  = Join-Path $BeDir '.env'

# Cổng publish ra máy chủ: Spring Boot, Vue, Flutter web, MariaDB, Redis
$PortList = @(8081, 5374, 5375, 3307, 6380)

if ([string]::IsNullOrWhiteSpace($PublicHost)) {
    $fromEnv = [Environment]::GetEnvironmentVariable('PUBLIC_HOST')
    if ([string]::IsNullOrWhiteSpace($fromEnv)) { $PublicHost = 'localhost' } else { $PublicHost = $fromEnv }
}
# Hai biến này được docker compose đọc (PUBLIC_HOST nhúng vào bản build Vue và Flutter)
$env:PUBLIC_HOST     = $PublicHost
$env:DOCKER_BUILDKIT = '1'

# ---------------------------------------------------------------------------
# In màu
# ---------------------------------------------------------------------------
function Write-Step([string]$Message) { Write-Host ''; Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host $Message -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host $Message -ForegroundColor Yellow }
function Write-Fail([string]$Message) { Write-Host $Message -ForegroundColor Red }

# ---------------------------------------------------------------------------
# Gọi Docker
# ---------------------------------------------------------------------------

# Chạy một khối lệnh "thăm dò", trả $true nếu lệnh native thoát với mã 0.
# Tạm đặt Continue để stderr của docker không bị PowerShell 5.1 coi là lỗi dừng script.
function Test-Probe([scriptblock]$Block) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $null = & $Block 2>&1
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previous
    }
}

# docker <args>: hiện kết quả ra màn hình, ném lỗi nếu mã thoát khác 0.
function Invoke-Docker {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker @args } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) {
        throw "Lệnh 'docker $($args -join ' ')' thất bại (mã thoát $LASTEXITCODE)."
    }
}

# docker <args>: trả về chuỗi kết quả, bỏ qua stderr; chuỗi rỗng nếu lỗi.
function Invoke-DockerCapture {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & docker @args 2>$null } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0 -or $null -eq $out) { return '' }
    return (($out | ForEach-Object { "$_" }) -join "`n").Trim()
}

function Invoke-Compose([string]$File, [string[]]$ComposeArgs) {
    Invoke-Docker compose -f $File @ComposeArgs
}

# ---------------------------------------------------------------------------
# Kiểm tra môi trường
# ---------------------------------------------------------------------------
function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Chưa cài Docker. Cài Docker Desktop: https://docs.docker.com/desktop/setup/install/windows-install/'
    }
    if (-not (Test-Probe { docker compose version })) {
        throw "Chưa có Docker Compose v2 (lệnh 'docker compose' không chạy). Cập nhật Docker Desktop lên bản mới."
    }
    if (-not (Test-Probe { docker info })) {
        throw "Không kết nối được Docker engine. Mở Docker Desktop, đợi đến khi báo 'Engine running' rồi chạy lại."
    }
    $osType = Invoke-DockerCapture info --format '{{.OSType}}'
    if ($osType -eq 'windows') {
        throw "Docker đang ở chế độ Windows containers. Bấm chuột phải biểu tượng Docker ở khay hệ thống, chọn 'Switch to Linux containers'."
    }
    $memText = Invoke-DockerCapture info --format '{{.MemTotal}}'
    $memBytes = 0.0
    if ([double]::TryParse($memText, [ref]$memBytes) -and $memBytes -gt 0) {
        $memGb = [math]::Round($memBytes / 1GB, 1)
        if ($memGb -lt 4) {
            Write-Warn "Docker chỉ được cấp khoảng $memGb GB RAM. Lần build đầu (Gradle, Flutter) có thể bị tắt giữa chừng (mã 137). Tăng RAM bằng file %UserProfile%\.wslconfig (memory=6GB) rồi chạy 'wsl --shutdown'."
        }
    }
}

function Assert-Files {
    $missing = $false
    $required = @(
        $BeCompose, (Join-Path $BeDir 'Dockerfile'),
        $FeCompose, (Join-Path $FeDir 'Dockerfile'),
        $AppCompose, (Join-Path $AppDir 'Dockerfile')
    )
    foreach ($f in $required) {
        if (-not (Test-Path -LiteralPath $f -PathType Leaf)) {
            Write-Fail "Thiếu file: $f"
            $missing = $true
        }
    }
    if ($missing) {
        throw 'Hãy chạy script ở thư mục gốc của repo (cạnh app_mobile, be-web-admin, fe_web_admin).'
    }

    if (-not (Test-Path -LiteralPath $BeEnvFile -PathType Leaf)) {
        throw "Thiếu $BeEnvFile (chứa MARIADB_*, REDIS_*, TOTP_MASTER_KEY_V2...). Tạo nhanh bằng: .\build-containers.ps1 init"
    }
    $envText = Get-Content -LiteralPath $BeEnvFile -Raw
    if ($null -eq $envText) { $envText = '' }
    foreach ($key in @('MARIADB_USERNAME', 'MARIADB_PASSWORD', 'MARIADB_DBS', 'REDIS_PASSWORD', 'TOTP_MASTER_KEY_V2')) {
        if ($envText -notmatch "(?m)^$key=.+") {
            throw "File .env của be-web-admin thiếu hoặc để trống: $key"
        }
    }
    $userMatch = [regex]::Match($envText, '(?m)^MARIADB_USERNAME=(.*)$')
    if ($userMatch.Success -and $userMatch.Groups[1].Value.Trim() -ne 'root') {
        Write-Warn "MARIADB_USERNAME khác 'root': docker-compose chỉ tạo tài khoản root nên Spring Boot sẽ không đăng nhập được MariaDB."
    }
    if ($envText.Contains("`r")) {
        Write-Warn 'File be-web-admin\.env đang dùng CRLF. Nên lưu dạng LF để giá trị không dính ký tự xuống dòng.'
    }
}

# Trả về các Dockerfile đang bị CRLF. Dockerfile dùng heredoc ghi script sh và cấu hình Nginx
# vào image Linux; nếu bị CRLF thì dòng "#!/bin/sh" có thêm ký tự \r và script không chạy được.
function Find-CrlfDockerfiles {
    foreach ($dir in @($BeDir, $FeDir, $AppDir)) {
        $f = Join-Path $dir 'Dockerfile'
        if (Test-Path -LiteralPath $f -PathType Leaf) {
            $bytes = [System.IO.File]::ReadAllBytes($f)
            if ([Array]::IndexOf($bytes, [byte]13) -ge 0) { $f }
        }
    }
}

function Assert-LineEndings {
    $bad = @(Find-CrlfDockerfiles)
    if ($bad.Count -gt 0) {
        $list = ($bad | ForEach-Object { "  - $_" }) -join "`n"
        throw "Dockerfile đang dùng CRLF (thường do git tự đổi khi clone trên Windows):`n$list`nChạy: .\build-containers.ps1 fix-eol   (rồi build lại)"
    }
}

function Invoke-FixEol {
    $bad = @(Find-CrlfDockerfiles)
    if ($bad.Count -eq 0) { Write-Ok 'Các Dockerfile đã dùng LF, không cần sửa.'; return }
    foreach ($f in $bad) {
        $bytes = [System.IO.File]::ReadAllBytes($f)
        $out = New-Object -TypeName 'System.Collections.Generic.List[byte]' -ArgumentList $bytes.Length
        for ($i = 0; $i -lt $bytes.Length; $i++) {
            # Bỏ ký tự CR đứng trước LF, hoặc CR nằm lẻ ở cuối file
            if ($bytes[$i] -eq 13 -and ((($i + 1) -ge $bytes.Length) -or $bytes[$i + 1] -eq 10)) { continue }
            $out.Add($bytes[$i])
        }
        [System.IO.File]::WriteAllBytes($f, $out.ToArray())
        Write-Ok "Đã đổi sang LF: $f"
    }
}

# Dải cổng mà Windows (Hyper-V, WinNAT) giữ chỗ; Docker không bind được cổng nằm trong đó.
function Get-ExcludedPortRange {
    try {
        $lines = & netsh interface ipv4 show excludedportrange protocol=tcp 2>$null
        foreach ($line in $lines) {
            if ($line -match '^\s*(\d+)\s+(\d+)') {
                [pscustomobject]@{ Start = [int]$Matches[1]; End = [int]$Matches[2] }
            }
        }
    } catch { }
}

# Trả về số cổng có vấn đề (chỉ cảnh báo, không chặn).
function Test-Ports {
    $dockerPorts = ''
    try { $dockerPorts = Invoke-DockerCapture ps --format '{{.Ports}}' } catch { }
    $excluded = @(Get-ExcludedPortRange)
    $problems = 0

    foreach ($p in $PortList) {
        foreach ($r in $excluded) {
            if ($p -ge $r.Start -and $p -le $r.End) {
                Write-Warn "Cổng $p nằm trong dải $($r.Start)-$($r.End) do Windows giữ chỗ, Docker có thể không bind được. Mở PowerShell quyền Admin: net stop winnat ; net start winnat (hoặc đổi cổng)."
                $problems++
            }
        }

        $listeners = @()
        try { $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue) } catch { }
        if ($listeners.Count -gt 0 -and $dockerPorts -notmatch ":$p->") {
            $names = @($listeners | ForEach-Object {
                $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
                if ($proc) { $proc.ProcessName }
            } | Sort-Object -Unique) -join ', '
            if (-not $names) { $names = 'không rõ' }
            Write-Warn "Cảnh báo: cổng $p đang được tiến trình khác dùng ($names), ví dụ MariaDB/Redis/Spring chạy cục bộ. Hãy tắt trước khi chạy."
            $problems++
        }
    }
    return $problems
}

function Initialize-Network {
    if (-not (Test-Probe { docker network inspect $Network })) {
        Write-Step "Tạo mạng Docker dùng chung: $Network"
        Invoke-Docker network create $Network | Out-Null
    }
}

# ---------------------------------------------------------------------------
# init: sinh be-web-admin\.env
# ---------------------------------------------------------------------------
function New-RandomPassword([int]$Length = 24) {
    # Bỏ các ký tự dễ nhầm (I, O, l, 0, 1) và ký tự đặc biệt ($, #, @...) để không vướng
    # cú pháp .env / docker compose / redis-server --requirepass.
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $limit = 256 - (256 % $alphabet.Length)   # loại bỏ lệch phân phối khi chia lấy dư
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $buffer = New-Object byte[] 1
    $sb = New-Object System.Text.StringBuilder
    try {
        while ($sb.Length -lt $Length) {
            $rng.GetBytes($buffer)
            if ($buffer[0] -lt $limit) { [void]$sb.Append($alphabet[$buffer[0] % $alphabet.Length]) }
        }
    } finally {
        $rng.Dispose()
    }
    return $sb.ToString()
}

function New-MasterKey {
    # Base64 của 32 byte ngẫu nhiên, tương đương: openssl rand -base64 32
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Initialize-EnvFile {
    if ((Test-Path -LiteralPath $BeEnvFile) -and -not $Force) {
        Write-Warn "Đã có $BeEnvFile nên giữ nguyên. Thêm -Force để tạo lại (file cũ được lưu thành .env.bak)."
        return
    }
    if (Test-Path -LiteralPath $BeEnvFile) {
        Copy-Item -LiteralPath $BeEnvFile -Destination ($BeEnvFile + '.bak') -Force
        Write-Warn 'Đã sao lưu file cũ thành .env.bak'
    }

    $dbPassword    = New-RandomPassword 24
    $redisPassword = New-RandomPassword 24
    $masterKey     = New-MasterKey

    # Chỉ dùng ASCII trong file này: Spring đọc .env như file .properties.
    $lines = @(
        '# be-web-admin/.env  (sinh boi: build-containers.ps1 init)',
        '# KHONG commit file nay. Dockerfile COPY no vao image vi app dung dotenv-java.',
        '',
        '# Chi dung khi chay Spring Boot truc tiep tren may (IDE / java -jar).',
        '# Trong Docker, host duoc ghi de bang ten dich vu "mariadb" va "redis".',
        'IP_ADDRESS=127.0.0.1',
        '',
        'SPRING_BOOT_PORT=8081',
        '',
        '# MariaDB: lang nghe cong 3307 trong container, publish ra 127.0.0.1:3307.',
        '# MARIADB_USERNAME phai la root vi docker-compose chi tao tai khoan root.',
        'MARIADB_PORT=3307',
        'MARIADB_DBS=ntt_university',
        'MARIADB_USERNAME=root',
        "MARIADB_PASSWORD=$dbPassword",
        '',
        '# Redis: lang nghe cong 6380 trong container, publish ra 127.0.0.1:6380.',
        'REDIS_PORT=6380',
        'REDIS_USERNAME=default',
        "REDIS_PASSWORD=$redisPassword",
        'REDIS_DBS=1',
        '',
        '# Khoa AES (Base64 cua 32 byte ngau nhien) ma hoa secret TOTP trong MariaDB.',
        '# Mat hoac doi khoa nay thi cac secret TOTP da kich hoat khong giai ma duoc nua.',
        "TOTP_MASTER_KEY_V2=$masterKey",
        '',
        '# update = tu tao bang khi database con trong (lan chay dau).',
        'JPA_DDL_AUTO=update'
    )
    $text = ($lines -join "`n") + "`n"
    # UTF-8 KHÔNG BOM, xuống dòng LF. Set-Content -Encoding UTF8 của PowerShell 5.1 thêm BOM,
    # làm hỏng tên biến đầu tiên của file .env.
    [System.IO.File]::WriteAllText($BeEnvFile, $text, (New-Object System.Text.UTF8Encoding($false)))

    Write-Ok "Đã tạo $BeEnvFile với mật khẩu và khóa ngẫu nhiên."
    Write-Warn 'Nếu đã chạy up trước đó, volume MariaDB/Redis vẫn giữ mật khẩu cũ: hãy chạy "clean" rồi "up" để khởi tạo lại.'
}

# ---------------------------------------------------------------------------
# Các lệnh chính
# ---------------------------------------------------------------------------
function Invoke-Build {
    Write-Step 'Build be-web-admin (Spring Boot)'
    Invoke-Compose $BeCompose @('build')
    Write-Step 'Build fe_web_admin (Vue + Nginx)'
    Invoke-Compose $FeCompose @('build')
    Write-Step 'Build app_mobile (Flutter web + Nginx), lần đầu có thể mất vài phút'
    Invoke-Compose $AppCompose @('build')
}

function Invoke-Up {
    Write-Step 'Chạy backend: MariaDB, Redis, Spring Boot'
    Invoke-Compose $BeCompose @('up', '-d', '--build')
    Write-Step 'Chạy Vue + Nginx'
    Invoke-Compose $FeCompose @('up', '-d', '--build')
    Write-Step 'Chạy Flutter web + Nginx (lần đầu build có thể mất vài phút)'
    Invoke-Compose $AppCompose @('up', '-d', '--build')

    Write-Host ''
    Write-Ok 'Đã khởi động xong. Truy cập:'
    Write-Host "  Vue web admin : http://${PublicHost}:5374"
    Write-Host "  Flutter web   : http://${PublicHost}:5375"
    Write-Host "  Spring Boot   : http://${PublicHost}:8081/api"
    Write-Host "  MariaDB       : ${PublicHost}:3307  (database lấy từ MARIADB_DBS, chỉ mở ở 127.0.0.1)"
    Write-Host "  Redis         : ${PublicHost}:6380  (db số 1, chỉ mở ở 127.0.0.1)"
    Write-Host ''
    Write-Host 'Spring Boot cần khoảng 30-60 giây để sẵn sàng. Xem log: .\build-containers.ps1 logs'

    if ($Open) { Start-Process "http://${PublicHost}:5374" }
}

function Invoke-Down {
    Write-Step 'Dừng container'
    foreach ($file in @($AppCompose, $FeCompose, $BeCompose)) {
        try { Invoke-Compose $file @('down', '--remove-orphans') } catch { Write-Warn $_.Exception.Message }
    }
}

function Invoke-Clean {
    Write-Warn 'Lệnh này xóa toàn bộ dữ liệu MariaDB và Redis trong Docker (volume).'
    if (-not $Force) {
        $answer = Read-Host "Gõ 'yes' để tiếp tục"
        if ($answer -ne 'yes') { Write-Host 'Đã hủy.'; return }
    }
    Write-Step 'Xóa container và volume'
    foreach ($file in @($AppCompose, $FeCompose, $BeCompose)) {
        try { Invoke-Compose $file @('down', '-v', '--remove-orphans') } catch { Write-Warn $_.Exception.Message }
    }
    $null = Test-Probe { docker network rm $Network }
    Write-Ok 'Đã dọn xong.'
}

function Invoke-Ps {
    Invoke-Docker ps -a --filter "network=$Network" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
}

function Invoke-Logs {
    # Gọi trực tiếp: Ctrl+C để thoát là bình thường, không coi là lỗi.
    & docker logs -f --tail 200 $Service
}

function Invoke-Apk {
    Write-Step 'Build file APK debug (x86_64) cho Android emulator, lần đầu rất lâu'
    Invoke-Compose $AppCompose @('--profile', 'apk', 'build', 'flutter-apk')
    Invoke-Compose $AppCompose @('--profile', 'apk', 'run', '--rm', 'flutter-apk')
    $apk = Join-Path (Join-Path $AppDir 'dist') 'app-debug.apk'
    if (Test-Path -LiteralPath $apk) {
        Write-Ok "Đã tạo: $apk"
        Write-Host "Cài lên emulator đang mở:  adb install -r `"$apk`""
        Write-Host 'Emulator gọi Spring Boot qua 10.0.2.2:8081, nên cần chạy "up" trước.'
    } else {
        Write-Warn "Không thấy $apk. Xem log build phía trên."
    }
}

function Invoke-Check([string]$Title, [scriptblock]$Block) {
    try {
        $null = & $Block
        Write-Host "[OK]  $Title" -ForegroundColor Green
        return $true
    } catch {
        Write-Host "[LOI] $Title" -ForegroundColor Red
        Write-Host "      $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

function Invoke-Doctor {
    Write-Step 'Kiểm tra môi trường'
    Write-Host "Windows    : $([Environment]::OSVersion.VersionString)"
    Write-Host "PowerShell : $($PSVersionTable.PSVersion)"
    Write-Host "PublicHost : $PublicHost"

    $allOk = $true
    if (-not (Invoke-Check 'Docker Desktop chạy được (Linux containers, Compose v2)' { Assert-Docker })) { $allOk = $false }
    if (-not (Invoke-Check 'Đủ file và be-web-admin\.env hợp lệ' { Assert-Files })) { $allOk = $false }
    if (-not (Invoke-Check 'Dockerfile dùng LF' { Assert-LineEndings })) { $allOk = $false }

    $portProblems = Test-Ports
    if ($portProblems -eq 0) {
        Write-Host "[OK]  Các cổng $($PortList -join ', ') đều rảnh hoặc do chính dự án giữ" -ForegroundColor Green
    } else {
        $allOk = $false
    }

    Write-Host ''
    if ($allOk) { Write-Ok 'Mọi thứ sẵn sàng. Chạy: .\build-containers.ps1 up' }
    else {
        Write-Warn 'Còn mục chưa đạt, xem các dòng [LOI] và cảnh báo phía trên.'
        $script:ExitCode = 1
    }
}

function Show-Usage {
    Get-Help -Name $PSCommandPath -Detailed
}

# ---------------------------------------------------------------------------
# Điều phối
# ---------------------------------------------------------------------------
function Invoke-Action([string]$Name) {
    if ($Name -eq 'status') { $Name = 'ps' }
    switch ($Name) {
        'init'    { Initialize-EnvFile }
        'fix-eol' { Invoke-FixEol }
        'help'    { Show-Usage }
        'doctor'  { Invoke-Doctor }
        'up'      { Assert-Docker; Assert-Files; Assert-LineEndings; $null = Test-Ports; Initialize-Network; Invoke-Up }
        'build'   { Assert-Docker; Assert-Files; Assert-LineEndings; Initialize-Network; Invoke-Build }
        'down'    { Assert-Docker; Invoke-Down }
        'clean'   { Assert-Docker; Invoke-Clean }
        'restart' { Assert-Docker; Invoke-Down; Assert-Files; Assert-LineEndings; Initialize-Network; Invoke-Up }
        'ps'      { Assert-Docker; Invoke-Ps }
        'logs'    { Assert-Docker; Invoke-Logs }
        'apk'     { Assert-Docker; Assert-Files; Assert-LineEndings; Initialize-Network; Invoke-Apk }
    }
}

function Show-Menu {
    $items = [ordered]@{
        '1' = 'up'; '2' = 'ps'; '3' = 'logs'; '4' = 'down'; '5' = 'clean'
        '6' = 'apk'; '7' = 'init'; '8' = 'doctor'; '9' = 'restart'
    }
    while ($true) {
        Clear-Host
        Write-Host '=====================================================' -ForegroundColor Cyan
        Write-Host '  TOTP 2FA - Docker trên Windows' -ForegroundColor Cyan
        Write-Host "  PublicHost: $PublicHost" -ForegroundColor Cyan
        Write-Host '=====================================================' -ForegroundColor Cyan
        Write-Host '  1. up       Build và chạy cả 5 container'
        Write-Host '  2. ps       Xem trạng thái'
        Write-Host '  3. logs     Xem log Spring Boot (Ctrl+C để thoát)'
        Write-Host '  4. down     Dừng container, giữ dữ liệu'
        Write-Host '  5. clean    Dừng và XÓA dữ liệu MariaDB, Redis'
        Write-Host '  6. apk      Build file APK cho Android emulator'
        Write-Host '  7. init     Tạo be-web-admin\.env (lần đầu)'
        Write-Host '  8. doctor   Kiểm tra môi trường'
        Write-Host '  9. restart  down rồi up'
        Write-Host '  0. Thoát'
        Write-Host ''
        $choice = Read-Host 'Chọn (0-9)'
        if ($choice -eq '0') { return }
        if ($items.Contains($choice)) {
            try { Invoke-Action $items[$choice] } catch { Write-Fail "LỖI: $($_.Exception.Message)" }
            Write-Host ''
            $null = Read-Host 'Nhấn Enter để quay lại menu'
        }
    }
}

try {
    if ([string]::IsNullOrEmpty($Action)) { Show-Menu } else { Invoke-Action $Action }
} catch {
    Write-Fail "LỖI: $($_.Exception.Message)"
    exit 1
}
exit $script:ExitCode
