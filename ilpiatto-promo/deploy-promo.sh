#!/usr/bin/env bash
# 홍보 사이트 배포: sudo bash deploy-promo.sh
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "sudo로 실행해주세요: sudo bash deploy-promo.sh"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"

chmod o+x /opt/app   # nginx(www-data)가 /opt/app 안으로 들어갈 수 있게 (목록 보기는 불가)
mkdir -p /opt/app/promo/images
cp "$HERE/index.html" /opt/app/promo/
cp -n "$HERE"/images/* /opt/app/promo/images/ 2>/dev/null || true   # 이미 있는 사진은 덮어쓰지 않음
chmod -R a+rX /opt/app/promo

cp "$HERE/nginx-ilpiatto" /etc/nginx/sites-available/ilpiatto
ln -sf /etc/nginx/sites-available/ilpiatto /etc/nginx/sites-enabled/ilpiatto
nginx -t && systemctl reload nginx

IP=$(hostname -I | awk '{print $1}')
echo "홍보 사이트:   http://$IP/"
echo "관리자 사이트: http://$IP/admin/  (로그인 필요)"
