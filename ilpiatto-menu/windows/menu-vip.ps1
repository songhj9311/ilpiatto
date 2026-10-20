﻿# 일피아토 메뉴판 — 주 서버(매장 Windows PC) 관리 스크립트 (Windows PowerShell 5.1, 관리자 권한)
#
# 하는 일
#  1) Windows 방화벽: 메뉴판 포트(8090)는 매장 Wi-Fi 대역에서 온 접속만 허용  ← "매장 Wi-Fi 전용"의 핵심
#  2) 포트 전달: Wi-Fi IP·VIP의 8090 → WSL Nginx 8090 (WSL 내부 IP가 바뀌면 자동으로 따라감)
#  3) 가상 IP(VIP): 메뉴판이 정상이면 QR 주소의 VIP를 이 PC가 갖고, 안 열리면 내려놓아 백업 서버가 가져가게 함
# 전제: Windows Wi-Fi가 고정 IP(172.30.1.48)로 설정되어 있어야 함 (DHCP면 보조 IP를 붙일 수 없음)
#
# 처음 한 번 등록:  powershell -ExecutionPolicy Bypass -File C:\ilpiatto\menu-vip.ps1 -Install
# 직접 실행:        powershell -ExecutionPolicy Bypass -File C:\ilpiatto\menu-vip.ps1

param([switch]$Install)

$WifiSubnet = '172.30.1.0/24'   # 매장 Wi-Fi 대역 (ipconfig의 IPv4 주소/서브넷 마스크)
$Vip        = '172.30.1.200'
$Prefix     = 24
$Iface      = 'Wi-Fi'            # 'Get-NetAdapter' 에 나오는 이름
$Port       = 8090
$Distro     = 'Ubuntu-24.04'
$CheckSec   = 5
$RuleName   = 'ilpiatto-menu-wifi-only'
$LogFile    = 'C:\ilpiatto\logs\menu-vip.log'

function Log($msg) {
    $line = "{0:yyyy-MM-dd HH:mm:ss} | {1}" -f (Get-Date), $msg
    Write-Host $line
    New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

if ($Install) {
    # 로그인할 때마다 관리자 권한으로 숨김 실행 (WSL은 사용자 계정에 묶여 있어서 SYSTEM이 아니라 내 계정으로)
    $action    = New-ScheduledTaskAction -Execute 'powershell.exe' `
                 -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $trigger   = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
    $settings  = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 99 `
                 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName 'ilpiatto-menu-vip' -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName 'ilpiatto-menu-vip'
    Write-Host "작업 스케줄러에 'ilpiatto-menu-vip' 등록 완료, 지금 실행했습니다. 로그: $LogFile"
    exit
}

# ── 1) 방화벽: 8090은 매장 Wi-Fi 대역만 ───────────────────────────────
function EnsureFirewall {
    # Windows는 '172.30.1.0/24'를 '172.30.1.0/255.255.255.0'으로 바꿔 저장하므로, 설명(Description)에 적은 대역으로 비교
    $rule = Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue
    if (-not $rule -or $rule.Description -ne $WifiSubnet -or $rule.Enabled -ne 'True') {
        if ($rule) { Remove-NetFirewallRule -Name $RuleName }
        New-NetFirewallRule -Name $RuleName -DisplayName 'Il Piatto menu (store Wi-Fi only)' -Description $WifiSubnet `
            -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
            -RemoteAddress $WifiSubnet -Profile Any | Out-Null
        Log "방화벽: $Port 번은 $WifiSubnet 에서만 허용"
    }
    # 다른 규칙이 8090을 아무 데서나 열어 두면 Wi-Fi 제한이 무너지므로 꺼 둔다
    Get-NetFirewallPortFilter -Protocol TCP -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -contains "$Port" } |
        Get-NetFirewallRule -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $RuleName -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' -and $_.Enabled -eq 'True' } |
        ForEach-Object {
            $ra = ($_ | Get-NetFirewallAddressFilter).RemoteAddress
            if ($ra -contains 'Any') {
                Disable-NetFirewallRule -Name $_.Name
                Log "방화벽: '$($_.DisplayName)' 규칙이 $Port 번을 전체에 열고 있어 끔"
            }
        }
}

# ── 2) 포트 전달: (Wi-Fi IP, VIP):8090 → WSL:8090 ────────────────────
# 0.0.0.0 으로 열면 WSL이 쓰는 127.0.0.1:8090 과 겹칠 수 있어서, 주소를 하나씩 지정한다 (8088과 같은 방식)
function WslIp {
    try { ((wsl -d $Distro --exec hostname -I) -split '\s+')[0] } catch { $null }
}

function MainIp {
    (Get-NetIPAddress -InterfaceAlias $Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -ne $Vip } | Select-Object -First 1).IPAddress
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

function EnsurePortProxy {
    $wsl = WslIp
    SetProxy (MainIp) $wsl            # 백업 서버의 동기화·감시용 (172.30.1.48:8090)
    if (HaveVip) { SetProxy $Vip $wsl } # 손님 QR 주소용 (172.30.1.200:8090)
}

# ── 3) 가상 IP ─────────────────────────────────────────────────────
function MenuOk {
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$Port/menu/" -UseBasicParsing -TimeoutSec 2
        return ($r.StatusCode -eq 200 -and $r.Content -match 'Il Piatto')
    } catch { return $false }
}

function HaveVip { [bool](Get-NetIPAddress -IPAddress $Vip -ErrorAction SilentlyContinue) }

function VipInUse {
    # 다른 기기(백업 서버)가 VIP를 쓰고 있는지. 'TTL=' 이 있어야 진짜 응답
    $out = ping -n 1 -w 1000 $Vip
    return [bool]($out -match 'TTL=')
}

# 지난 버전(8088)에서 만든 VIP 전용 포트 전달이 남아 있으면 정리
netsh interface portproxy delete v4tov4 listenaddress=$Vip listenport=8088 2>$null | Out-Null

Log "감시 시작: Wi-Fi 대역 $WifiSubnet, VIP $Vip, 포트 $Port"
$waitLogged = $false
$tick = 0
while ($true) {
    if ($tick % 12 -eq 0) { EnsureFirewall; EnsurePortProxy }   # 1분마다 방화벽·포트 전달 점검
    $tick++

    $ok   = MenuOk
    $have = HaveVip

    if ($ok -and -not $have) {
        if (VipInUse) {
            if (-not $waitLogged) { Log "메뉴판 정상, 백업 서버가 VIP를 반납할 때까지 대기"; $waitLogged = $true }
        } else {
            # ActiveStore: 재부팅하면 사라짐 → 꺼졌다 켜질 때 백업과 주소가 겹치지 않음
            New-NetIPAddress -InterfaceAlias $Iface -IPAddress $Vip -PrefixLength $Prefix `
                -SkipAsSource $true -PolicyStore ActiveStore -ErrorAction Stop | Out-Null
            SetProxy $Vip (WslIp)
            Log "[주 서버] VIP $Vip 가져옴"
            $waitLogged = $false
        }
    } elseif (-not $ok -and $have) {
        Remove-NetIPAddress -IPAddress $Vip -Confirm:$false -ErrorAction SilentlyContinue
        netsh interface portproxy delete v4tov4 listenaddress=$Vip listenport=$Port 2>$null | Out-Null
        Log "[주 서버] 메뉴판 응답 없음 → VIP 내려놓음 (백업 서버가 가져감)"
        EnsurePortProxy   # WSL이 다시 켜지며 IP가 바뀐 경우를 바로 따라감
    }

    Start-Sleep -Seconds $CheckSec
}
