#!/usr/bin/env bash
# 일피아토 메뉴판 백업 서버 완전 제거 — setup_backup.sh가 바꾼 것을 전부 되돌림
# 사용법:  sudo bash /opt/ilpiatto-menu-backup/uninstall_backup.sh
# 보관된 메뉴판 버전(/var/backups/ilpiatto-menu)과 로그(/var/log/ilpiatto)는 지우지 않음
set -uo pipefail
[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요: sudo bash $0"; exit 1; }

ENV_FILE=/etc/ilpiatto/menu-backup.env
VIP=172.30.1.200; VIP_PREFIX=24
[[ -f "$ENV_FILE" ]] && { set -a; source "$ENV_FILE"; set +a; }

echo "== 1. 감시·동기화 중지 (감시가 멈추면서 VIP를 내려놓음)"
systemctl disable --now ilpiatto-menu-failover.service ilpiatto-menu-sync.timer ilpiatto-menu-sync.service 2>/dev/null
rm -f /etc/systemd/system/ilpiatto-menu-failover.service \
      /etc/systemd/system/ilpiatto-menu-sync.service \
      /etc/systemd/system/ilpiatto-menu-sync.timer
systemctl daemon-reload

echo "== 2. 가상 IP가 남아 있으면 제거"
for dev in $(ip -4 -o addr show | awk -v v="$VIP" '$4 ~ "^"v"/" {print $2}'); do
  ip addr del "$VIP/$VIP_PREFIX" dev "$dev" && echo "   $dev 에서 $VIP 제거"
done

echo "== 3. Nginx 메뉴판 설정 제거"
rm -f /etc/nginx/sites-enabled/menu-backup /etc/nginx/sites-available/menu-backup
if [[ ! -e /etc/nginx/sites-enabled/default && -e /etc/nginx/sites-available/default ]]; then
  ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default   # 설치 때 지운 기본 사이트 복구
fi
nginx -t 2>/dev/null && systemctl reload-or-restart nginx

echo "== 4. 방화벽 규칙 제거"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw delete allow from "${WIFI_SUBNET:-172.30.1.0/24}" to any port 8090 proto tcp >/dev/null 2>&1 && echo "   ufw 8090 규칙 제거"
fi

echo "== 5. 프로그램·설정 제거"
rm -rf /opt/ilpiatto-menu-backup /var/www/ilpiatto/menu
rm -f "${STOP_FILE:-/etc/ilpiatto/menu-backup.STOP}"
[[ -f "$ENV_FILE" ]] && mv "$ENV_FILE" "$ENV_FILE.removed" && echo "   설정은 $ENV_FILE.removed 로 남겨 둠"

echo
echo "제거 완료. 남은 것: /var/backups/ilpiatto-menu (메뉴판 버전), /var/log/ilpiatto (로그)"
