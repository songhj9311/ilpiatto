# ─────────────────────────────────────────────────────────────────────
# deploy/deploy.sh 에 붙여 넣을 블록 — "== 5. Nginx" 바로 앞에 넣는다
# (지금 deploy.sh는 홍보 폴더에서 index.html과 images만 복사하므로,
#  메뉴판은 이 블록이 있어야 push할 때 서버에 반영된다)
# 고친 뒤 서버에서 다시 설치:  sudo install -o root -g root -m 755 deploy/deploy.sh /opt/app/bin/deploy.sh
# ─────────────────────────────────────────────────────────────────────
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

# ── "== 6. 동작 확인" 맨 끝에 한 줄 추가 ──
# curl -sf -o /dev/null http://127.0.0.1:8090/menu/ && echo "메뉴판 정상" || { echo "메뉴판 응답 없음: sudo tail /var/log/ilpiatto/menu-error.log"; exit 1; }
