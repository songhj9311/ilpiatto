#!/usr/bin/env bash
# 일피아토 메뉴 가격 관리 사이트 배포 스크립트 (Ubuntu 24.04)
# 사용법: 이 폴더(ilpiato-site)를 서버에 올린 뒤
#   cd ~/ilpiato-site && sudo bash deploy.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "sudo로 실행해주세요: sudo bash deploy.sh"; exit 1
fi
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "[1/6] DB 테이블 생성"
sudo -u postgres psql -d ilpiato_db -v ON_ERROR_STOP=1 < "$HERE/sql/schema.sql"
sudo -u postgres psql -d ilpiato_db -c "ALTER TABLE store_config OWNER TO ilpiato; ALTER TABLE menu OWNER TO ilpiato;"

echo "[2/6] Python API 복사"
cp "$HERE/python-api/main.py" "$HERE/python-api/pricing.py" /opt/app/python-api/
chown appuser:appuser /opt/app/python-api/*.py

echo "[3/6] Java API 복사 및 빌드"
JAVA_SRC=/opt/app/java-api/src/main/java/com/ilpiato/api
cp "$HERE"/java-api/src/main/java/com/ilpiato/api/*.java "$JAVA_SRC/"
cp "$HERE/java-api/src/main/resources/application.properties" /opt/app/java-api/src/main/resources/
chown -R songhojun:songhojun /opt/app/java-api
sudo -u songhojun bash -c "cd /opt/app/java-api && ./gradlew bootJar -q"

echo "[4/6] 화면 파일 복사"
mkdir -p /opt/app/web
cp "$HERE/web/index.html" /opt/app/web/

echo "[5/6] Nginx 설정 (관리자 비밀번호)"
apt-get install -y -qq apache2-utils >/dev/null
if [[ ! -f /etc/nginx/.htpasswd ]]; then
  read -rp "관리자 사이트 로그인 아이디: " ADMIN_USER
  htpasswd -c /etc/nginx/.htpasswd "$ADMIN_USER"
fi
cp "$HERE/nginx/ilpiato" /etc/nginx/sites-available/ilpiato
ln -sf /etc/nginx/sites-available/ilpiato /etc/nginx/sites-enabled/ilpiato
nginx -t

echo "[6/6] 서비스 재시작"
systemctl restart python-api java-api
systemctl reload nginx
echo "완료: 브라우저에서 http://$(hostname -I | awk '{print $1}')/ 로 접속하세요."
