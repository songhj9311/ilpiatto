#!/usr/bin/env bash
# 일피아또 배포 스크립트 — GitHub 저장소 내용을 서버 실행 위치로 반영
# 설치 위치: /opt/app/bin/deploy.sh (root 소유, 다른 계정은 수정 불가)
# 사용법:   sudo /opt/app/bin/deploy.sh <저장소 폴더>
set -euo pipefail

# ── 저장소 안의 폴더 이름 (다르면 여기만 수정) ──────────────────
ADMIN_DIR="ilpiatto-site"      # 관리자 사이트 (sql, python-api, java-api, web)
PROMO_DIR="ilpiatto-promo"     # 홍보 사이트 (index.html, images, nginx-ilpiatto)

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요"; exit 1; }
SRC="$(realpath "${1:?저장소 폴더를 지정하세요. 예: sudo deploy.sh ~/ilpiatto}")"
ADMIN="$SRC/$ADMIN_DIR"
PROMO="$SRC/$PROMO_DIR"
[[ -f "$ADMIN/sql/schema.sql" ]] || { echo "$ADMIN/sql/schema.sql 이 없습니다. ADMIN_DIR 이름을 확인하세요"; exit 1; }
[[ -f "$PROMO/index.html" ]]     || { echo "$PROMO/index.html 이 없습니다. PROMO_DIR 이름을 확인하세요"; exit 1; }

# 내용이 바뀐 파일만 복사하고 바뀐 목록을 출력 (안 바뀌면 빈 문자열)
# -t(시간 보존)를 빼야 git이 파일 시간을 바꿔도 "변경"으로 보지 않음
sync_dir() { rsync -ri --checksum "$@"; }

echo "== 1. DB 스키마"
sudo -u postgres psql -q -d ilpiatto_db -v ON_ERROR_STOP=1 < "$ADMIN/sql/schema.sql"

echo "== 2. Python API"
out=$(sync_dir --include='*.py' --exclude='*' "$ADMIN/python-api/" /opt/app/python-api/)
if [[ -n "$out" ]]; then
  echo "$out"; chown -R appuser:appuser /opt/app/python-api; systemctl restart python-api
else
  echo "변경 없음"
fi

echo "== 3. Java API"
out=$(sync_dir "$ADMIN/java-api/src/" /opt/app/java-api/src/)
if [[ -n "$out" ]]; then
  echo "$out"
  chown -R appuser:appuser /opt/app/java-api
  sudo -u appuser bash -c 'cd /opt/app/java-api && ./gradlew bootJar -q'
  systemctl restart java-api
else
  echo "변경 없음"
fi

echo "== 4. 화면 (관리자 /admin/, 홍보 /)"
chmod o+x /opt/app        # nginx(www-data)가 /opt/app 안을 지나갈 수 있게
mkdir -p /opt/app/web /opt/app/promo/images
sync_dir "$ADMIN/web/" /opt/app/web/
# 홍보 폴더에서는 index.html과 사진만 공개 위치로 복사 (배포 스크립트·설정 파일은 제외)
sync_dir --include='index.html' --include='images/' --exclude='images/*.txt' \
         --include='images/**' --exclude='*' "$PROMO/" /opt/app/promo/
chmod -R a+rX /opt/app/web /opt/app/promo

echo "== 4-1. 메뉴판 (8090, 매장 Wi-Fi 전용)"
MENU="$SRC/ilpiatto-menu"
if [[ -f "$MENU/menu/index.html" ]]; then
  mkdir -p /opt/app/menu
  sync_dir "$MENU/menu/index.html" "$MENU/server/wifi-only.html" /opt/app/menu/
  chmod -R a+rX /opt/app/menu
  MCONF=/etc/nginx/sites-available/ilpiatto-menu
  if ! cmp -s "$MENU/server/nginx-menu" "$MCONF"; then
    [[ -f "$MCONF" ]] && cp "$MCONF" "$MCONF.bak"
    cp "$MENU/server/nginx-menu" "$MCONF"
    ln -sf "$MCONF" /etc/nginx/sites-enabled/ilpiatto-menu
    if nginx -t; then
      systemctl reload-or-restart nginx
    else
      echo "메뉴판 Nginx 설정 오류: 이전 설정으로 되돌립니다"
      if [[ -f "$MCONF.bak" ]]; then cp "$MCONF.bak" "$MCONF"; else rm -f /etc/nginx/sites-enabled/ilpiatto-menu; fi
      exit 1
    fi
  fi
else
  echo "메뉴판 없음 (건너뜀)"
fi

echo "== 5. Nginx"
CONF=/etc/nginx/sites-available/ilpiatto
if ! cmp -s "$PROMO/nginx-ilpiatto" "$CONF"; then
  [[ -f "$CONF" ]] && cp "$CONF" "$CONF.bak"
  cp "$PROMO/nginx-ilpiatto" "$CONF"
  ln -sf "$CONF" /etc/nginx/sites-enabled/ilpiatto
  if nginx -t; then
    systemctl reload-or-restart nginx   # 켜져 있으면 reload, 꺼져 있으면 시작
  else
    echo "Nginx 설정 오류: 이전 설정으로 되돌립니다"
    [[ -f "$CONF.bak" ]] && cp "$CONF.bak" "$CONF"
    exit 1
  fi
else
  echo "변경 없음"
fi
# 설정이 그대로여도 Nginx가 꺼져 있으면 켠다
systemctl is-active --quiet nginx || systemctl start nginx

echo "== 6. 동작 확인"
wait_ok() {  # $1=서비스 이름 $2=주소, 최대 90초 대기
  for _ in $(seq 1 45); do
    curl -sf -o /dev/null "$2" && { echo "$1 정상"; return 0; }
    sleep 2
  done
  echo "$1 응답 없음: journalctl -u $1 -n 50 으로 로그를 확인하세요"; return 1
}
wait_ok python-api http://127.0.0.1:8000/health
wait_ok java-api   http://127.0.0.1:8080/java/health
curl -sf -o /dev/null http://127.0.0.1:8088/ && echo "홍보 사이트 정상" || { echo "홍보 사이트 응답 없음: sudo tail /var/log/ilpiatto/nginx-error.log"; exit 1; }
curl -sf -o /dev/null http://127.0.0.1:8090/menu/ && echo "메뉴판 정상" || { echo "메뉴판 응답 없음: sudo tail /var/log/ilpiatto/menu-error.log"; exit 1; }

echo "배포 완료: $(git -C "$SRC" log -1 --format='%h %s' 2>/dev/null || date)"
