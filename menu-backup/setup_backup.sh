#!/usr/bin/env bash
# 일피아토 메뉴판 백업 서버 설치 (Ubuntu 24.04 LTS, 매장 안 백업용 PC/노트북에서 실행)
# 다시 실행해도 안전함: 설정 파일(/etc/ilpiatto/menu-backup.env)은 덮어쓰지 않고 나머지만 다시 맞춤
#
# 사용법:  sudo bash setup_backup.sh            → Python 버전으로 설치
#          sudo bash setup_backup.sh java       → Java 버전으로 설치
set -euo pipefail

LANG_CHOICE="${1:-python}"
SRC="$(cd "$(dirname "$0")" && pwd)"
APP=/opt/ilpiatto-menu-backup
ENV_FILE=/etc/ilpiatto/menu-backup.env
WEB_ROOT=/var/www/ilpiatto/menu
PORT=8090

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요: sudo bash $0 $LANG_CHOICE"; exit 1; }
[[ "$LANG_CHOICE" == python || "$LANG_CHOICE" == java ]] || { echo "python 또는 java 중 하나를 고르세요"; exit 1; }

echo "== 1. 패키지 설치 =="
apt-get update -qq
apt-get install -y -qq nginx iproute2 iputils-arping iputils-ping curl python3 >/dev/null
if [[ "$LANG_CHOICE" == java ]]; then
  apt-get install -y -qq openjdk-21-jre-headless >/dev/null
fi
timedatectl set-timezone Asia/Seoul

echo "== 2. 폴더와 설정 파일 =="
install -d -m 755 "$APP" "$WEB_ROOT" /etc/ilpiatto
install -d -m 750 /var/backups/ilpiatto-menu /var/log/ilpiatto
install -m 644 "$SRC"/menu_sync.py "$SRC"/failover_watch.py "$SRC"/MenuSync.java "$SRC"/FailoverWatch.java "$APP"/
install -m 755 "$SRC"/uninstall_backup.sh "$APP"/
if [[ ! -f "$ENV_FILE" ]]; then
  install -m 640 "$SRC"/menu-backup.env "$ENV_FILE"
  echo "   설정 파일 생성: $ENV_FILE"
else
  echo "   기존 설정 유지: $ENV_FILE"
fi
set -a; source "$ENV_FILE"; set +a
[[ "${WIFI_SUBNET:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$ ]] || { echo "   !! $ENV_FILE 의 WIFI_SUBNET 값이 잘못됨: '${WIFI_SUBNET:-}'"; exit 1; }
echo "   매장 Wi-Fi 대역: $WIFI_SUBNET  /  주 서버: $MAIN_URL  /  가상 IP: $VIP"

echo "== 3. 메뉴판 파일 =="
install -m 644 "$SRC/wifi-only.html" "$WEB_ROOT/wifi-only.html"
if [[ ! -f "$WEB_ROOT/index.html" && -f "$SRC/menu/index.html" ]]; then
  install -m 644 "$SRC/menu/index.html" "$WEB_ROOT/index.html"
  echo "   함께 들어 있던 메뉴판으로 먼저 채움 (주 서버와 동기화되면 바뀜)"
fi

echo "== 4. Nginx ($PORT번, 매장 Wi-Fi만) =="
sed "s#__WIFI_SUBNET__#$WIFI_SUBNET#" "$SRC/nginx-menu-backup" > /etc/nginx/sites-available/menu-backup
chmod 644 /etc/nginx/sites-available/menu-backup
ln -sf /etc/nginx/sites-available/menu-backup /etc/nginx/sites-enabled/menu-backup
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx >/dev/null
systemctl reload-or-restart nginx

echo "== 5. 방화벽 =="
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow from "$WIFI_SUBNET" to any port "$PORT" proto tcp >/dev/null
  echo "   ufw: $PORT ← $WIFI_SUBNET 만 허용"
else
  echo "   ufw 꺼져 있음 (Nginx가 매장 Wi-Fi 밖 접속을 거부함)"
fi

echo "== 6. 서비스 등록 ($LANG_CHOICE) =="
if [[ "$LANG_CHOICE" == python ]]; then
  SYNC_CMD="/usr/bin/python3 $APP/menu_sync.py"
  WATCH_CMD="/usr/bin/python3 -u $APP/failover_watch.py"
else
  SYNC_CMD="/usr/bin/java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $APP/MenuSync.java"
  WATCH_CMD="/usr/bin/java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $APP/FailoverWatch.java"
fi

cat > /etc/systemd/system/ilpiatto-menu-sync.service <<EOF
[Unit]
Description=Il Piatto menu sync (main -> backup)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
EnvironmentFile=$ENV_FILE
Environment=LANG=C.UTF-8 PYTHONIOENCODING=utf-8
ExecStart=$SYNC_CMD
StandardOutput=append:/var/log/ilpiatto/menu-sync.log
StandardError=append:/var/log/ilpiatto/menu-sync.log
EOF

cat > /etc/systemd/system/ilpiatto-menu-sync.timer <<EOF
[Unit]
Description=Il Piatto menu sync every 5 minutes

[Timer]
OnBootSec=1min
OnUnitActiveSec=5min
Persistent=true

[Install]
WantedBy=timers.target
EOF

cat > /etc/systemd/system/ilpiatto-menu-failover.service <<EOF
[Unit]
Description=Il Piatto menu failover watch (VIP takeover)
After=network-online.target nginx.service
Wants=network-online.target

[Service]
EnvironmentFile=$ENV_FILE
Environment=LANG=C.UTF-8 PYTHONIOENCODING=utf-8
ExecStart=$WATCH_CMD
Restart=always
RestartSec=5
StandardOutput=append:/var/log/ilpiatto/menu-failover.log
StandardError=append:/var/log/ilpiatto/menu-failover.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload

echo "== 7. 첫 동기화 =="
systemctl start ilpiatto-menu-sync.service || true
tail -n 1 /var/log/ilpiatto/menu-sync.log || true
if [[ ! -f "$WEB_ROOT/index.html" ]]; then
  echo "   !! 주 서버($MAIN_URL)에서 메뉴판을 못 받았고, 함께 넣은 메뉴판도 없습니다."
  echo "   !! 메뉴판 없이 가상 IP를 가져가면 손님이 빈 화면을 보므로 여기서 멈춥니다."
  exit 1
fi

systemctl enable --now ilpiatto-menu-sync.timer >/dev/null

echo "== 8. 안전 점검 (매장 환경인지) =="
# 2단계에서 source 한 설정값(GATEWAY_MAC 등)이 export 되어 있어서 그대로 전달됨
if LANG=C.UTF-8 PYTHONIOENCODING=utf-8 bash -c "$WATCH_CMD --check"; then
  systemctl enable ilpiatto-menu-failover.service >/dev/null
  systemctl restart ilpiatto-menu-failover.service   # 설정을 바꾸고 다시 실행했을 때도 반영되게
  FAILOVER_ON=1
else
  systemctl disable --now ilpiatto-menu-failover.service >/dev/null 2>&1 || true
  FAILOVER_ON=0
  echo
  echo "   !! 안전 점검 실패 → 장애 전환(가상 IP)은 켜지 않았습니다. 메뉴판 사본·동기화만 동작합니다."
  echo "   !! 매장에서 위의 공유기 MAC(과 Wi-Fi 이름)을 $ENV_FILE 에 적고 다시 실행하세요: sudo bash $0 $LANG_CHOICE"
fi

echo "== 9. 확인 =="
sleep 2
curl -s -o /dev/null -w "   백업 서버 메뉴판 (이 PC에서): HTTP %{http_code}\n" "http://127.0.0.1:$PORT/menu/"
if [[ $FAILOVER_ON == 1 ]]; then
  systemctl is-active --quiet ilpiatto-menu-failover && echo "   장애 전환 감시: 실행 중" || echo "   장애 전환 감시: 실패 (journalctl -u ilpiatto-menu-failover)"
else
  echo "   장애 전환 감시: 꺼짐 (안전 점검 실패)"
fi
tail -n 1 /var/log/ilpiatto/menu-failover.log || true
echo
echo "설치 완료. 이 PC 주소: $(hostname -I | awk '{print $1}')  /  QR 주소: http://$VIP:$PORT/menu/"
echo "비상 정지: sudo touch $STOP_FILE   /   완전 제거: sudo bash $APP/uninstall_backup.sh"
