#!/usr/bin/env python3
"""일피아토 주간 로그 백업

일주일에 한 번 실행되어 다음을 순서대로 한다.
  1. /var/log/ilpiatto 안의 로그 전부를 tar.gz 한 파일로 묶는다
  2. 묶은 파일을 열어 빠진 파일이 없는지 확인한다
  3. 윈도우 쪽 폴더(C:\\ilpiatto\\backup\\logs)에도 복사한다
  4. 확인이 끝난 뒤에만 원본 로그를 비우고(사용 중인 .log) 나머지는 삭제한다
  5. 보관 기간(KEEP_WEEKS)이 지난 예전 백업을 지운다

설치 위치: /opt/app/bin/log_backup.py (root로 실행)
수동 실행: sudo python3 /opt/app/bin/log_backup.py
"""
import fcntl
import os
import shutil
import socket
import sys
import tarfile
from datetime import datetime, timedelta
from pathlib import Path

LOG_DIR = Path(os.getenv("LOG_DIR", "/var/log/ilpiatto"))
BACKUP_DIR = Path(os.getenv("BACKUP_DIR", "/var/backups/ilpiatto-logs"))
# 윈도우 C:\ilpiatto\backup\logs. WSL이 망가져도 백업이 남도록 한 부 더 둔다 (없으면 건너뜀)
WINDOWS_DIR = Path(os.getenv("WINDOWS_DIR", "/mnt/c/ilpiatto/backup/logs"))
KEEP_WEEKS = int(os.getenv("KEEP_WEEKS", "12"))  # 백업 보관 주 수. 0이면 지우지 않음
LOCK_FILE = "/run/ilpiatto-log-backup.lock"


def say(msg: str) -> None:
    # systemd 타이머로 실행되면 이 출력이 journalctl 에 남는다
    print(f"[log-backup] {msg}", flush=True)


def collect_logs() -> list[Path]:
    """로그 폴더의 일반 파일을 전부 모은다 (빈 파일 제외)."""
    return sorted(p for p in LOG_DIR.iterdir() if p.is_file() and p.stat().st_size > 0)


def make_archive(files: list[Path]) -> Path:
    today = datetime.now()
    start = (today - timedelta(days=7)).strftime("%Y%m%d")
    name = f"ilpiatto-logs_{start}-{today:%Y%m%d}_{today:%H%M%S}.tar.gz"
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(BACKUP_DIR, 0o750)  # 접속 IP가 들어 있으므로 아무나 못 읽게
    target = BACKUP_DIR / name
    tmp = target.with_suffix(".part")
    with tarfile.open(tmp, "w:gz") as tar:
        for f in files:
            tar.add(f, arcname=f.name)
    tmp.rename(target)  # 다 쓴 다음에만 정식 이름으로 (중간에 끊기면 .part만 남음)
    os.chmod(target, 0o640)
    return target


def verify_archive(archive: Path, files: list[Path]) -> None:
    """백업 파일을 다시 열어 모든 로그가 들어 있고 크기가 맞는지 확인한다."""
    with tarfile.open(archive, "r:gz") as tar:
        members = {m.name: m.size for m in tar.getmembers()}
    for f in files:
        if f.name not in members:
            raise RuntimeError(f"백업에 {f.name} 이(가) 없습니다")
        # 백업하는 동안 로그가 더 쌓일 수 있으므로 '백업 크기 > 0'만 확인
        if members[f.name] == 0:
            raise RuntimeError(f"백업 속 {f.name} 크기가 0입니다")


def copy_to_windows(archive: Path) -> None:
    if not Path("/mnt/c").is_dir():
        say("윈도우 C: 드라이브가 보이지 않아 윈도우 복사는 건너뜁니다")
        return
    try:
        WINDOWS_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(archive, WINDOWS_DIR / archive.name)
        say(f"윈도우 복사 완료: {WINDOWS_DIR / archive.name}")
    except OSError as e:  # 윈도우 복사는 보조이므로 실패해도 계속
        say(f"윈도우 복사 실패(서버 쪽 백업은 정상): {e}")


def clear_logs(files: list[Path]) -> None:
    """사용 중인 *.log 는 내용만 비우고, 나머지(스프링이 나눈 .gz 등)는 삭제한다.

    .log 를 지우지 않고 비우는 이유: Nginx·Python·Java가 파일을 열어 둔 채로
    이어 쓰고 있어서, 지우면 프로그램을 재시작할 때까지 보이지 않는 파일에 계속 쓴다.
    세 프로그램 모두 '이어쓰기(append)' 모드라 비운 뒤에는 파일 처음부터 다시 쓴다.
    """
    for f in files:
        if f.suffix == ".log":
            os.truncate(f, 0)
        else:
            f.unlink()


def prune_old_backups() -> None:
    if KEEP_WEEKS <= 0:
        return
    limit = datetime.now() - timedelta(weeks=KEEP_WEEKS)
    for folder in (BACKUP_DIR, WINDOWS_DIR):
        if not folder.is_dir():
            continue
        for old in folder.glob("ilpiatto-logs_*.tar.gz"):
            if datetime.fromtimestamp(old.stat().st_mtime) < limit:
                old.unlink()
                say(f"보관 기간({KEEP_WEEKS}주)이 지난 백업 삭제: {old}")


def main() -> int:
    if os.geteuid() != 0:
        say("sudo로 실행하세요")
        return 1
    if not LOG_DIR.is_dir():
        say(f"{LOG_DIR} 폴더가 없습니다. setup-logging.sh 를 먼저 실행하세요")
        return 1

    # 타이머와 수동 실행이 겹쳐도 한 번만 돌게 잠금
    with open(LOCK_FILE, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            say("이미 실행 중입니다")
            return 0

        files = collect_logs()
        if not files:
            say("백업할 로그가 없습니다")
            prune_old_backups()
            return 0

        archive = make_archive(files)
        verify_archive(archive, files)  # 실패하면 예외 → 원본을 지우지 않고 종료
        size_kb = archive.stat().st_size / 1024
        say(f"백업 완료({socket.gethostname()}): {archive} ({len(files)}개, {size_kb:.0f}KB)")

        copy_to_windows(archive)
        clear_logs(files)
        say("원본 로그 정리 완료: " + ", ".join(f.name for f in files))
        prune_old_backups()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # 실패하면 원본은 그대로 남는다
        say(f"실패 — 원본 로그는 지우지 않았습니다: {e}")
        sys.exit(1)
