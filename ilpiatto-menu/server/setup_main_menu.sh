#!/usr/bin/env bash
# 일피아토 메뉴판 — 주 서버(WSL Ubuntu 24.04) 처음 한 번 설치
# 사용법:  cd ~/ilpiatto && sudo bash ilpiatto-menu/server/setup_main_menu.sh
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요: sudo bash $0"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
MENU_SRC="$HERE/../menu/index.html"
[[ -f "$MENU_SRC" ]] || { echo "$MENU_SRC 가 없습니다. 먼저 python3 make_menu.py 로 메뉴판을 만드세요"; exit 1; }

echo "== 1. 메뉴판 폴더 (/opt/app/menu)"
chmod o+x /opt/app
install -d -m 755 /opt/app/menu
install -d -m 750 /var/log/ilpiatto
install -m 644 "$MENU_SRC" /opt/app/menu/index.html
install -m 644 "$HERE/wifi-only.html" /opt/app/menu/wifi-only.html

echo "== 2. Nginx (8090번, 사설 IP만)"
install -m 644 "$HERE/nginx-menu" /etc/nginx/sites-available/ilpiatto-menu
ln -sf /etc/nginx/sites-available/ilpiatto-menu /etc/nginx/sites-enabled/ilpiatto-menu
nginx -t
systemctl reload-or-restart nginx

echo "== 3. Ubuntu 방화벽 (8090은 사설 대역에서만)"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
    ufw allow from "$net" to any port 8090 proto tcp >/dev/null
  done
  echo "   ufw: 8090 ← 10/8, 172.16/12, 192.168/16 허용"
else
  echo "   ufw 꺼져 있음 (건너뜀, Nginx와 Windows 방화벽이 막음)"
fi

echo "== 4. 확인"
curl -s -o /dev/null -w "   메뉴판  http://127.0.0.1:8090/menu/  → HTTP %{http_code}\n" http://127.0.0.1:8090/menu/
echo
echo "다음: Windows 관리자 PowerShell에서 menu-vip.ps1 -Install 실행 (방화벽·포트 전달·가상 IP)"
