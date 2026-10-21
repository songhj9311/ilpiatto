# 일피아토 메뉴판 — 주 서버(매장 Windows PC) 관리 스크립트 (Windows PowerShell 5.1, 관리자 권한)
#
# 하는 일
#  1) Windows 방화벽: 메뉴판 포트(8090)는 매장 Wi-Fi 대역에서 온 접속만 허용
#  2) 포트 전달: Wi-Fi IP·VIP의 8090 → WSL Nginx 8090
#  3) 가상 IP(VIP): 메뉴판이 정상이면 QR 주소의 VIP를 이 PC가 갖고, 안 열리면 내려놓음
#
# 안전장치 (하나라도 안 맞으면 네트워크를 전혀 건드리지 않음)
#  - 이 PC 이름이 $StorePc 와 같을 것
#  - 지금 연결된 Wi-Fi 이름(SSID)이 $StoreSsid 와 같을 것
#  - 공유기(기본 게이트웨이) MAC 주소가 $GatewayMac 과 같을 것  → 집 공유기도 172.30.1.x 라서 IP만으로는 구별이 안 됨
#  - Wi-Fi가 고정 IP일 것 (자동(DHCP) 상태에서 IP를 추가하면 Windows가 DHCP를 꺼서 인터넷이 끊김)
#  - Wi-Fi IP가 $MainIp 이고 $WifiSubnet 안에 있을 것
#  - C:\ilpiatto\menu-vip.STOP 파일이 없을 것 (비상 정지 스위치)
#  VIP를 붙인 뒤 공유기 응답이 끊기면 즉시 되돌리고 STOP 파일을 만들어 멈춤.
#
# 사용법 (관리자 PowerShell)
#  점검만:    powershell -ExecutionPolicy Bypass -File C:\ilpiatto\menu-vip.ps1 -Check      ← 아무것도 안 바꿈
#  등록:      powershell -ExecutionPolicy Bypass -File C:\ilpiatto\menu-vip.ps1 -Install    ← 점검 통과해야 등록
#  완전 제거: powershell -ExecutionPolicy Bypass -File C:\ilpiatto\menu-vip.ps1 -Uninstall  ← 바꾼 것 전부 되돌림
#  비상 정지: New-Item C:\ilpiatto\menu-vip.STOP   (다시 켜기: Remove-Item C:\ilpiatto\menu-vip.STOP)

param([switch]$Install, [switch]$Uninstall, [switch]$Check)

# ── 매장 정보 (-Check 결과를 보고 채우기) ─────────────────────────────
$StorePc    = ''                 # 매장 PC 이름 (예: DESKTOP-KUTJHQ8)
$StoreSsid  = ''                 # 매장 Wi-Fi 이름
$GatewayMac = ''                 # 매장 공유기 MAC (예: 00-11-22-33-44-55)
$MainIp     = '172.30.1.14'      # 이 PC의 고정 IP
$WifiSubnet = '172.30.1.0/24'    # 매장 Wi-Fi 대역
$Vip        = '172.30.1.200'
$Prefix     = 24
$Iface      = 'Wi-Fi'            # 'Get-NetAdapter' 에 나오는 이름
$Port       = 8090
$Distro     = 'Ubuntu-24.04'

$CheckSec   = 5
$RuleName   = 'ilpiatto-menu-wifi-only'
$TaskName   = 'ilpiatto-menu-vip'
$BaseDir    = 'C:\ilpiatto'
$LogFile    = "$BaseDir\logs\menu-vip.log"
$StopFile   = "$BaseDir\menu-vip.STOP"
$StateFile  = "$BaseDir\menu-vip.state.json"

function Log($msg) {
    $line = "{0:yyyy-MM-dd HH:mm:ss} | {1}" -f (Get-Date), $msg
    Write-Host $line
    New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function IsAdmin {
    ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ── 변경 기록 (제거할 때 정확히 되돌리기 위해) ─────────────────────────
function LoadState {
    if (Test-Path $StateFile) {
        try { return (Get-Content $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json) } catch {}
    }
    return [pscustomobject]@{ DisabledRules = @() }
}
function SaveState($s) {
    New-Item -ItemType Directory -Force -Path $BaseDir | Out-Null
    $s | ConvertTo-Json | Set-Content -Path $StateFile -Encoding UTF8
}

# ── 현재 네트워크 정보 ─────────────────────────────────────────────
function NormMac($m) { if ($m) { ($m -replace '[:\.]', '-').ToUpper() } else { '' } }

function InSubnet($ip, $cidr) {
    try {
        $net, $bits = $cidr.Split('/')
        $mask = [uint32]0
        if ([int]$bits -gt 0) { $mask = [uint32]([math]::Pow(2, 32) - [math]::Pow(2, 32 - [int]$bits)) }
        $toInt = { param($a) $b = ([Net.IPAddress]::Parse($a)).GetAddressBytes(); [Array]::Reverse($b); [BitConverter]::ToUInt32($b, 0) }
        return ((& $toInt $ip) -band $mask) -eq ((& $toInt $net) -band $mask)
    } catch { return $false }
}

function GetFacts {
    $f = [ordered]@{}
    $f.PcName = $env:COMPUTERNAME
    $ssidLine = (netsh wlan show interfaces) | Where-Object { $_ -match '^\s*SSID\s*:' } | Select-Object -First 1
    $f.Ssid = if ($ssidLine) { ($ssidLine -replace '^\s*SSID\s*:\s*', '').Trim() } else { '' }
    $ipif = Get-NetIPInterface -InterfaceAlias $Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue
    $f.Dhcp = if ($ipif) { "$($ipif.Dhcp)" } else { '(Wi-Fi 장치 없음)' }
    $f.Ip = (Get-NetIPAddress -InterfaceAlias $Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue |
             Where-Object { $_.IPAddress -ne $Vip } | Select-Object -First 1).IPAddress
    $gw = Get-NetRoute -InterfaceAlias $Iface -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
          Sort-Object RouteMetric | Select-Object -First 1
    $f.Gateway = if ($gw) { $gw.NextHop } else { '' }
    $f.GatewayMac = ''
    if ($f.Gateway) {
        ping -n 1 -w 1000 $f.Gateway | Out-Null
        $n = Get-NetNeighbor -IPAddress $f.Gateway -InterfaceAlias $Iface -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($n) { $f.GatewayMac = NormMac $n.LinkLayerAddress }
    }
    return $f
}

# 안전 점검: 통과하면 빈 목록, 안 맞으면 이유 목록
function SafetyProblems($f) {
    $p = @()
    if (Test-Path $StopFile)                    { $p += "비상 정지 파일이 있음: $StopFile ($(Get-Content $StopFile -Raw -ErrorAction SilentlyContinue))" }
    if (-not $StorePc -or -not $StoreSsid -or -not $GatewayMac) { $p += "스크립트 맨 위 매장 정보(StorePc, StoreSsid, GatewayMac)가 비어 있음 → -Check 결과로 채우기" }
    if ($StorePc -and $f.PcName -ne $StorePc)   { $p += "PC 이름이 다름: 지금 '$($f.PcName)', 매장 '$StorePc'" }
    if ($StoreSsid -and $f.Ssid -ne $StoreSsid) { $p += "Wi-Fi가 다름: 지금 '$($f.Ssid)', 매장 '$StoreSsid'" }
    if ($GatewayMac -and $f.GatewayMac -ne (NormMac $GatewayMac)) { $p += "공유기가 다름: 지금 MAC '$($f.GatewayMac)', 매장 '$(NormMac $GatewayMac)'" }
    if ($f.Dhcp -ne 'Disabled')                 { $p += "Wi-Fi가 고정 IP가 아님 (DHCP: $($f.Dhcp)) → 이 상태에서 IP를 추가하면 인터넷이 끊김" }
    if ($f.Ip -ne $MainIp)                      { $p += "Wi-Fi IP가 다름: 지금 '$($f.Ip)', 설정 '$MainIp'" }
    if ($f.Ip -and -not (InSubnet $f.Ip $WifiSubnet)) { $p += "Wi-Fi IP '$($f.Ip)'가 매장 대역 $WifiSubnet 밖" }
    if (-not $f.Gateway)                        { $p += "기본 게이트웨이(공유기)가 없음" }
    return ,$p
}

function GatewayOk($gw) {
    if (-not $gw) { return $false }
    return [bool]((ping -n 2 -w 1000 $gw) -match 'TTL=')
}

function Trip($reason) {
    # 비상 정지: 바꾼 것을 되돌리고 STOP 파일을 남김 (재부팅해도 다시 안 켜짐)
    ReleaseVip
    Set-Content -Path $StopFile -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $reason" -Encoding UTF8
    Log "[비상 정지] $reason → VIP 되돌리고 멈춤. 원인 확인 후 $StopFile 을 지우면 다시 동작"
}

# ── 1) 방화벽 ─────────────────────────────────────────────────────
function EnsureFirewall {
    $rule = Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue
    if (-not $rule -or $rule.Description -ne $WifiSubnet -or "$($rule.Enabled)" -ne 'True') {
        if ($rule) { Remove-NetFirewallRule -Name $RuleName }
        New-NetFirewallRule -Name $RuleName -DisplayName 'Il Piatto menu (store Wi-Fi only)' -Description $WifiSubnet `
            -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
            -RemoteAddress $WifiSubnet -Profile Any | Out-Null
        Log "방화벽: $Port 번은 $WifiSubnet 에서만 허용"
    }
    $state = LoadState
    Get-NetFirewallPortFilter -Protocol TCP -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -contains "$Port" } |
        Get-NetFirewallRule -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $RuleName -and "$($_.Direction)" -eq 'Inbound' -and "$($_.Action)" -eq 'Allow' -and "$($_.Enabled)" -eq 'True' } |
        ForEach-Object {
            $ra = ($_ | Get-NetFirewallAddressFilter).RemoteAddress
            if ($ra -contains 'Any') {
                Disable-NetFirewallRule -Name $_.Name
                $state.DisabledRules = @($state.DisabledRules) + $_.Name | Select-Object -Unique
                SaveState $state
                Log "방화벽: '$($_.DisplayName)' 규칙이 $Port 번을 전체에 열고 있어 끔 (제거할 때 다시 켬)"
            }
        }
}

# ── 2) 포트 전달 ──────────────────────────────────────────────────
function WslIp {
    try { ((wsl -d $Distro --exec hostname -I) -split '\s+')[0] } catch { $null }
}

function SetProxy($listen, $wsl) {
    if (-not $listen -or -not $wsl) { return }
    $exists = netsh interface portproxy show v4tov4 |
              Select-String "^\s*$([regex]::Escape($listen))\s+$Port\s+$([regex]::Escape($wsl))\s+$Port\s*$"
    if (-not $exists) {
        netsh interface portproxy delete v4tov4 listenaddress=$listen listenport=$Port 2>$null | Out-Null
        netsh interface portproxy add v4tov4 listenaddress=$listen listenport=$Port connectaddress=$wsl connectport=$Port | Out-Null
        Log "포트 전달: ${listen}:$Port -> WSL ${wsl}:$Port"
    }
}

function DelProxy($listen) {
    netsh interface portproxy delete v4tov4 listenaddress=$listen listenport=$Port 2>$null | Out-Null
}

# ── 3) 가상 IP ────────────────────────────────────────────────────
function MenuOk {
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$Port/menu/" -UseBasicParsing -TimeoutSec 2
        return ($r.StatusCode -eq 200 -and $r.Content -match 'Il Piatto')
    } catch { return $false }
}

function HaveVip { [bool](Get-NetIPAddress -IPAddress $Vip -ErrorAction SilentlyContinue) }

function VipInUse { [bool]((ping -n 1 -w 1000 $Vip) -match 'TTL=') }

function ReleaseVip {
    if (HaveVip) {
        Remove-NetIPAddress -IPAddress $Vip -Confirm:$false -ErrorAction SilentlyContinue
        Log "VIP $Vip 내려놓음"
    }
    DelProxy $Vip
}

function TakeVip($facts) {
    $dhcpBefore = $facts.Dhcp
    New-NetIPAddress -InterfaceAlias $Iface -IPAddress $Vip -PrefixLength $Prefix `
        -SkipAsSource $true -PolicyStore ActiveStore -ErrorAction Stop | Out-Null
    Start-Sleep -Seconds 2
    # 붙인 뒤 확인: 원래 IP·고정 IP 상태·공유기 응답이 그대로인지
    $after = Get-NetIPInterface -InterfaceAlias $Iface -AddressFamily IPv4
    $ipStill = [bool](Get-NetIPAddress -IPAddress $MainIp -ErrorAction SilentlyContinue)
    if ("$($after.Dhcp)" -ne $dhcpBefore -or -not $ipStill -or -not (GatewayOk $facts.Gateway)) {
        Trip "VIP를 붙인 뒤 네트워크가 이상함 (DHCP $dhcpBefore→$($after.Dhcp), 원래 IP 유지 $ipStill)"
        return $false
    }
    SetProxy $Vip (WslIp)
    Log "[주 서버] VIP $Vip 가져옴"
    return $true
}

# ── 점검 모드 ─────────────────────────────────────────────────────
if ($Check) {
    $f = GetFacts
    Write-Host ""
    Write-Host "== 지금 이 PC의 값 (매장 PC에서 실행해서 스크립트 맨 위에 적기) =="
    Write-Host ("  PC 이름      : {0}" -f $f.PcName)
    Write-Host ("  Wi-Fi 이름   : {0}" -f $f.Ssid)
    Write-Host ("  공유기 MAC   : {0}  (게이트웨이 {1})" -f $f.GatewayMac, $f.Gateway)
    Write-Host ("  Wi-Fi IP     : {0}" -f $f.Ip)
    Write-Host ("  DHCP         : {0}  (Disabled 여야 함 = 고정 IP)" -f $f.Dhcp)
    Write-Host ""
    $p = SafetyProblems $f
    if ($p.Count -eq 0) { Write-Host "== 점검 통과: 이 PC에서 -Install 해도 됩니다 ==" -ForegroundColor Green }
    else {
        Write-Host "== 점검 실패: 이 PC에서는 아무것도 바꾸지 않습니다 ==" -ForegroundColor Yellow
        $p | ForEach-Object { Write-Host "  - $_" }
    }
    exit
}

if (-not (IsAdmin)) { Write-Host "관리자 PowerShell에서 실행하세요."; exit 1 }

# ── 완전 제거 ─────────────────────────────────────────────────────
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -match 'menu-vip\.ps1' -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if (HaveVip) { Remove-NetIPAddress -IPAddress $Vip -Confirm:$false -ErrorAction SilentlyContinue }
    DelProxy $Vip
    DelProxy $MainIp
    Remove-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue
    $state = LoadState
    foreach ($n in @($state.DisabledRules)) {
        if ($n) { Enable-NetFirewallRule -Name $n -ErrorAction SilentlyContinue; Write-Host "  방화벽 규칙 다시 켬: $n" }
    }
    Remove-Item $StateFile -ErrorAction SilentlyContinue
    Write-Host "제거 완료: 작업 스케줄러, VIP, 8090 포트 전달, 방화벽 규칙을 되돌렸습니다."
    Write-Host "Wi-Fi 고정 IP 설정은 직접 바꾼 것이라 그대로 둡니다 (자동으로 되돌리려면: Set-NetIPInterface -InterfaceAlias $Iface -Dhcp Enabled)."
    exit
}

# ── 등록 ──────────────────────────────────────────────────────────
if ($Install) {
    $p = SafetyProblems (GetFacts)
    if ($p.Count -gt 0) {
        Write-Host "등록하지 않았습니다. 점검 실패:" -ForegroundColor Yellow
        $p | ForEach-Object { Write-Host "  - $_" }
        Write-Host "매장 PC에서 -Check 로 값을 확인하고 스크립트 맨 위를 채운 뒤 다시 실행하세요."
        exit 1
    }
    $action    = New-ScheduledTaskAction -Execute 'powershell.exe' `
                 -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $trigger   = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
    $settings  = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 99 `
                 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                 -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "작업 스케줄러에 '$TaskName' 등록 완료, 지금 실행했습니다. 로그: $LogFile"
    exit
}

# ── 감시 루프 ─────────────────────────────────────────────────────
$mutex = New-Object Threading.Mutex($false, 'Global\ilpiatto-menu-vip')
if (-not $mutex.WaitOne(0)) { Write-Host "이미 실행 중입니다 (작업 스케줄러 또는 다른 창)."; exit }

netsh interface portproxy delete v4tov4 listenaddress=$Vip listenport=8088 2>$null | Out-Null   # 지난 버전 흔적 정리

Log "감시 시작: 매장 PC '$StorePc', Wi-Fi '$StoreSsid', VIP $Vip, 포트 $Port"
$safe = $false
$lastProblems = ''
$waitLogged = $false
$tick = 0
try {
    while ($true) {
        # 1분마다(처음 포함) 매장 환경인지 다시 확인
        if ($tick % 12 -eq 0 -or (Test-Path $StopFile)) {   # 비상 정지는 바로 반영
            $facts = GetFacts
            $problems = SafetyProblems $facts
            if ($problems.Count -gt 0) {
                $joined = $problems -join ' / '
                if ($joined -ne $lastProblems) { Log "[대기] 매장 환경이 아님 → 아무것도 바꾸지 않음: $joined"; $lastProblems = $joined }
                if (HaveVip) { ReleaseVip }
                $safe = $false
            } else {
                if (-not $safe) { Log "매장 환경 확인됨 (Wi-Fi '$($facts.Ssid)', 공유기 $($facts.GatewayMac))"; $lastProblems = '' }
                $safe = $true
                EnsureFirewall
                SetProxy $MainIp (WslIp)
                if (HaveVip) {
                    if (GatewayOk $facts.Gateway) { SetProxy $Vip (WslIp) }
                    else { Trip "VIP를 가진 동안 공유기 응답이 끊김" ; $safe = $false }
                }
            }
        }
        $tick++

        if ($safe) {
            $ok   = MenuOk
            $have = HaveVip
            if ($ok -and -not $have) {
                if (VipInUse) {
                    if (-not $waitLogged) { Log "메뉴판 정상, 백업 서버가 VIP를 반납할 때까지 대기"; $waitLogged = $true }
                } else {
                    if (-not (TakeVip $facts)) { $safe = $false }
                    $waitLogged = $false
                }
            } elseif (-not $ok -and $have) {
                ReleaseVip
                Log "[주 서버] 메뉴판 응답 없음 → 백업 서버가 가져가도록 VIP 내려놓음"
            }
        }
        Start-Sleep -Seconds $CheckSec
    }
} finally {
    $mutex.ReleaseMutex()
}
