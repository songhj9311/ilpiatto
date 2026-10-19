#!/usr/bin/env bash
# 일피아토 로그 기록 + 주간 백업 설치 (처음 한 번만 실행)
# 사용법: 저장소의 ilpiatto-site/ops 폴더에서  sudo bash setup-logging.sh
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "== 1. 로그 폴더 /var/log/ilpiatto"
# appuser(Python·Java)가 쓸 수 있고, 다른 계정은 읽지 못하게
install -d -o appuser -g adm -m 2750 /var/log/ilpiatto
for f in python-api.log java-api.log; do
  touch "/var/log/ilpiatto/$f"; chown appuser:adm "/var/log/ilpiatto/$f"; chmod 640 "/var/log/ilpiatto/$f"
done

echo "== 2. 백업 스크립트와 타이머 설치"
install -d -m 755 /opt/app/bin
install -o root -g root -m 755 "$HERE/log_backup.py" /opt/app/bin/log_backup.py
install -m 644 "$HERE/ilpiatto-log-backup.service" /etc/systemd/system/
install -m 644 "$HERE/ilpiatto-log-backup.timer"   /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now ilpiatto-log-backup.timer

echo "== 3. 시스템 로그(journal)도 일주일만 보관"
install -d /etc/systemd/journald.conf.d
printf '[Journal]\nMaxRetentionSec=7day\n' > /etc/systemd/journald.conf.d/ilpiatto.conf
systemctl restart systemd-journald

echo "== 4. 앱 재시작 (새 로그 설정 적용)"
systemctl restart python-api java-api
nginx -t && systemctl reload-or-restart nginx

echo
echo "설치 완료. 다음 실행 예정:"
systemctl list-timers ilpiatto-log-backup.timer --no-pager
