"""
일피아토 메뉴판 동기화 (백업 서버에서 5분마다 실행)

주 서버의 메뉴판을 받아 와서, 정상 파일이고 내용이 바뀌었을 때만 백업 서버에 반영한다.
주 서버가 꺼져 있으면 아무것도 바꾸지 않고 마지막 정상본을 그대로 둔다.

Python 3.12+, 표준 라이브러리만 사용
"""
import hashlib
import os
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

MAIN_URL = os.environ.get("MAIN_URL", "http://172.30.1.14:8090/menu/")
WEB_DIR = Path(os.environ.get("WEB_DIR", "/var/www/ilpiatto/menu"))
ARCHIVE_DIR = Path(os.environ.get("ARCHIVE_DIR", "/var/backups/ilpiatto-menu"))
KEEP_VERSIONS = int(os.environ.get("KEEP_VERSIONS", "20"))
MUST_CONTAIN = os.environ.get("MUST_CONTAIN", "Il Piatto")
MIN_BYTES = 1024


def log(msg: str) -> None:
    print(f"{datetime.now():%Y-%m-%d %H:%M:%S} | {msg}", flush=True)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ilpiatto-menu-sync", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=5) as res:
        if res.status != 200:
            raise RuntimeError(f"HTTP {res.status}")
        return res.read()


def is_valid(body: bytes) -> str | None:
    """정상 메뉴판이면 None, 아니면 이유를 돌려준다."""
    if len(body) < MIN_BYTES:
        return f"파일이 너무 작음 ({len(body)} bytes)"
    if MUST_CONTAIN.encode() not in body:
        return f"'{MUST_CONTAIN}' 글자가 없음 (오류 페이지일 가능성)"
    return None


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_atomic(target: Path, data: bytes) -> None:
    """임시 파일에 다 쓴 뒤 한 번에 바꿔치기 → 손님이 반쯤 쓰인 파일을 볼 일이 없다."""
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    tmp.write_bytes(data)
    tmp.chmod(0o644)
    os.replace(tmp, target)


def archive(data: bytes) -> Path:
    ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    path = ARCHIVE_DIR / f"menu_{datetime.now():%Y%m%d_%H%M%S}.html"
    path.write_bytes(data)
    versions = sorted(ARCHIVE_DIR.glob("menu_*.html"))
    for old in versions[:-KEEP_VERSIONS] if KEEP_VERSIONS > 0 else []:
        old.unlink()
    return path


def main() -> int:
    target = WEB_DIR / "index.html"
    try:
        body = fetch(MAIN_URL)
    except Exception as e:
        log(f"주 서버 응답 없음 → 기존 메뉴판 유지 ({e})")
        return 0  # 주 서버 장애는 동기화 실패가 아님 (백업이 할 일을 하는 상황)

    reason = is_valid(body)
    if reason:
        log(f"받은 파일이 정상 메뉴판이 아님 → 반영 안 함: {reason}")
        return 1

    if target.exists() and sha256(target.read_bytes()) == sha256(body):
        log("변경 없음")
        return 0

    write_atomic(target, body)
    saved = archive(body)
    log(f"메뉴판 갱신 완료 ({len(body):,} bytes), 버전 보관: {saved.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
