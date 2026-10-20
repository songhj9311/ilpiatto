# 일피아또 서버 켜기 + 같은 Wi-Fi의 다른 기기에서 접속 가능하게 설정
# 실행: 관리자 PowerShell에서
#   powershell -ExecutionPolicy Bypass -File C:\ilpiatto\ilpiatto-start.ps1
# PC를 재부팅한 뒤에도 이 파일만 다시 실행하면 됩니다.

$distro     = "Ubuntu-24.04"   # wsl -l -v 에 나오는 Ubuntu 이름
$nginxPort  = 8088             # Ubuntu 안의 Nginx 포트 (nginx-ilpiatto 의 listen 값)
$publicPort = 8088             # 다른 기기가 접속할 포트

function Test-Url($url) {
    try {
        $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 5
        Write-Host "   [정상] $url ($($r.StatusCode))" -ForegroundColor Green
    } catch {
        Write-Host "   [실패] $url : $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "1) Ubuntu 켜기 (창을 닫아도 꺼지지 않게 백그라운드로 유지)"
$keeper = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" |
          Where-Object { $_.CommandLine -like "*sleep infinity*" }
if (-not $keeper) {
    Start-Process wsl.exe -ArgumentList "-d $distro --exec sleep infinity" -WindowStyle Hidden
    Start-Sleep -Seconds 8
}
wsl -d $distro -u root -- systemctl start nginx python-api java-api
$status = wsl -d $distro -- systemctl is-active nginx python-api java-api
Write-Host "   nginx, python-api, java-api : $($status -join ', ')"

Write-Host "2) Ubuntu 내부 주소 확인"
$ipOut = wsl -d $distro -- ip -4 -o addr show eth0
if ($ipOut -match 'inet (\d+\.\d+\.\d+\.\d+)') { $wslIp = $Matches[1] } else {
    Write-Host "   Ubuntu 주소를 찾지 못했습니다: $ipOut" -ForegroundColor Red; exit 1
}
Write-Host "   $wslIp"

Write-Host "3) Wi-Fi 주소의 $publicPort 포트로 들어온 요청을 Ubuntu로 전달"
$lanIp = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1).IPv4Address.IPAddress
netsh interface portproxy reset | Out-Null
netsh interface portproxy add v4tov4 listenport=$publicPort listenaddress=$lanIp connectport=$nginxPort connectaddress=$wslIp
if (-not (Get-NetFirewallRule -DisplayName "ilpiatto $publicPort" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "ilpiatto $publicPort" -Direction Inbound -Protocol TCP -LocalPort $publicPort -Action Allow | Out-Null
}

Write-Host "4) 접속 테스트"
Test-Url "http://${wslIp}:${nginxPort}/"     # Ubuntu 안의 Nginx
Test-Url "http://localhost:${nginxPort}/"    # 이 PC 브라우저용 주소
Test-Url "http://${lanIp}:${publicPort}/"    # 다른 기기용 주소

Write-Host ""
Write-Host "이 PC에서        : http://localhost:${nginxPort}/" -ForegroundColor Cyan
Write-Host "같은 Wi-Fi 기기에서: http://${lanIp}:${publicPort}/   (관리자: /admin/)" -ForegroundColor Cyan
