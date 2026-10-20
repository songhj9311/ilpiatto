"""
일피아토 메뉴판 장애 전환 감시 (백업 서버에서 항상 실행, root 권한 필요)

QR 주소의 가상 IP(VIP)를 평소에는 주 서버(Windows)가 갖고 있다.
 - 주 서버 메뉴판이 FAIL_LIMIT번 연속 응답 없음 + VIP를 아무도 안 쓰고 있음 → 백업 서버가 VIP를 가져옴
 - 백업이 VIP를 가진 상태에서 주 서버가 OK_LIMIT번 연속 정상 → VIP를 돌려줌

안전장치 (하나라도 안 맞으면 네트워크를 전혀 건드리지 않고 대기)
 - 이 PC의 IP가 매장 Wi-Fi 대역(WIFI_SUBNET) 안에 있을 것
 - 공유기(기본 게이트웨이) MAC이 GATEWAY_MAC과 같을 것 → 집 공유기도 172.30.1.x라 IP만으로는 구별이 안 됨
 - STORE_SSID를 적었다면 연결된 Wi-Fi 이름이 같을 것 (유선이면 비워 둠)
 - 비상 정지 파일(STOP_FILE)이 없을 것
 VIP를 붙인 뒤 공유기 응답이 끊기면 즉시 되돌리고 STOP 파일을 만들어 멈춘다.

실행:  python3 failover_watch.py           감시
       python3 failover_watch.py --check   현재 값과 점검 결과만 출력 (아무것도 안 바꿈)

Python 3.12+, 표준 라이브러리 + iproute2(ip), iputils-arping(arping), iputils-ping(ping)
"""
import ipaddress
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

MAIN_URL = os.environ.get("MAIN_URL", "http://172.30.1.14:8090/menu/")
WIFI_SUBNET = os.environ.get("WIFI_SUBNET", "172.30.1.0/24")
VIP = os.environ.get("VIP", "172.30.1.200")
VIP_PREFIX = os.environ.get("VIP_PREFIX", "24")
GATEWAY_MAC = os.environ.get("GATEWAY_MAC", "").strip()
STORE_SSID = os.environ.get("STORE_SSID", "").strip()
STOP_FILE = Path(os.environ.get("STOP_FILE", "/etc/ilpiatto/menu-backup.STOP"))
CHECK_SEC = float(os.environ.get("CHECK_SEC", "3"))
FAIL_LIMIT = int(os.environ.get("FAIL_LIMIT", "3"))
OK_LIMIT = int(os.environ.get("OK_LIMIT", "10"))
ENV_CHECK_EVERY = 20  # 감시 20번(약 1분)마다 매장 환경 재확인
MUST_CONTAIN = os.environ.get("MUST_CONTAIN", "Il Piatto").encode()


def log(msg: str) -> None:
    print(f"{datetime.now():%Y-%m-%d %H:%M:%S} | {msg}", flush=True)


def run(*cmd: str) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(cmd, 127, "", str(e))


def norm_mac(mac: str) -> str:
    return mac.strip().upper().replace("-", ":").replace(".", ":")


def detect_iface() -> str:
    """주 서버로 가는 경로의 네트워크 장치 이름 (예: wlp2s0, enp3s0)."""
    if os.environ.get("IFACE"):
        return os.environ["IFACE"]
    out = run("ip", "-o", "route", "get", urlparse(MAIN_URL).hostname).stdout.split()
    return out[out.index("dev") + 1] if "dev" in out else ""


IFACE = detect_iface()


# ── 매장 환경 확인 ─────────────────────────────────────────────────
def get_facts() -> dict:
    f = {"iface": IFACE, "ip": "", "gateway": "", "gateway_mac": "", "ssid": ""}
    for line in run("ip", "-4", "-o", "addr", "show", "dev", IFACE).stdout.splitlines():
        addr = line.split()[3].split("/")[0]
        if addr != VIP:
            f["ip"] = addr
            break
    route = run("ip", "-4", "route", "show", "default", "dev", IFACE).stdout.split()
    if "via" in route:
        f["gateway"] = route[route.index("via") + 1]
        run("ping", "-c", "1", "-W", "1", f["gateway"])  # ARP 표 채우기
        neigh = run("ip", "neigh", "show", f["gateway"], "dev", IFACE).stdout.split()
        if "lladdr" in neigh:
            f["gateway_mac"] = norm_mac(neigh[neigh.index("lladdr") + 1])
    if shutil.which("iwgetid"):
        f["ssid"] = run("iwgetid", "-r").stdout.strip()
    elif shutil.which("nmcli"):
        for line in run("nmcli", "-t", "-f", "active,ssid", "dev", "wifi").stdout.splitlines():
            if line.startswith("yes:"):
                f["ssid"] = line[4:]
    return f


def in_subnet(ip: str, cidr: str) -> bool:
    try:
        return ipaddress.ip_address(ip) in ipaddress.ip_network(cidr, strict=False)
    except ValueError:
        return False


def safety_problems(f: dict) -> list[str]:
    """통과하면 빈 목록, 안 맞으면 이유 목록."""
    p = []
    if STOP_FILE.exists():
        p.append(f"비상 정지 파일이 있음: {STOP_FILE} ({STOP_FILE.read_text(errors='replace').strip()})")
    if not f["iface"]:
        p.append("주 서버로 가는 네트워크 장치를 찾지 못함")
    if not GATEWAY_MAC:
        p.append("GATEWAY_MAC이 비어 있음 → 매장에서 --check 결과의 공유기 MAC을 menu-backup.env에 적기")
    elif f["gateway_mac"] != norm_mac(GATEWAY_MAC):
        p.append(f"공유기가 다름: 지금 MAC '{f['gateway_mac']}', 매장 '{norm_mac(GATEWAY_MAC)}'")
    if STORE_SSID and f["ssid"] != STORE_SSID:
        p.append(f"Wi-Fi가 다름: 지금 '{f['ssid']}', 매장 '{STORE_SSID}'")
    if not in_subnet(f["ip"], WIFI_SUBNET):
        p.append(f"이 PC의 IP '{f['ip']}'가 매장 대역 {WIFI_SUBNET} 밖")
    if not f["gateway"]:
        p.append("기본 게이트웨이(공유기)가 없음")
    return p


def gateway_ok(gw: str) -> bool:
    return bool(gw) and run("ping", "-c", "2", "-W", "1", gw).returncode == 0


# ── 주 서버·VIP ──────────────────────────────────────────────────
def main_alive() -> bool:
    try:
        req = urllib.request.Request(MAIN_URL, headers={"User-Agent": "ilpiatto-failover"})
        with urllib.request.urlopen(req, timeout=2) as res:
            return res.status == 200 and MUST_CONTAIN in res.read()
    except Exception:
        return False


def vip_here() -> bool:
    return f" {VIP}/" in run("ip", "-4", "-o", "addr", "show", "dev", IFACE).stdout


def vip_in_use() -> bool:
    """중복 주소 검사(DAD). arping -D는 아무도 응답하지 않을 때 0을 돌려준다."""
    return run("arping", "-D", "-q", "-c", "2", "-w", "3", "-I", IFACE, VIP).returncode != 0


def take_vip() -> None:
    r = run("ip", "addr", "add", f"{VIP}/{VIP_PREFIX}", "dev", IFACE)
    if r.returncode != 0 and "File exists" not in r.stderr:
        raise RuntimeError(r.stderr.strip())
    run("arping", "-U", "-c", "3", "-I", IFACE, "-s", VIP, VIP)  # 주변 기기 ARP 기록 갱신


def release_vip() -> None:
    if vip_here():
        run("ip", "addr", "del", f"{VIP}/{VIP_PREFIX}", "dev", IFACE)
        log(f"VIP {VIP} 내려놓음")


def trip(reason: str) -> None:
    """비상 정지: VIP를 되돌리고 STOP 파일을 남김 (재부팅·재시작해도 다시 안 켜짐)."""
    release_vip()
    STOP_FILE.parent.mkdir(parents=True, exist_ok=True)
    STOP_FILE.write_text(f"{datetime.now():%Y-%m-%d %H:%M:%S} {reason}\n")
    log(f"[비상 정지] {reason} → VIP 되돌리고 멈춤. 원인 확인 후 'sudo rm {STOP_FILE}' 하면 다시 동작")


def decide(holding: bool, alive: bool, fails: int, oks: int) -> tuple[str, int, int]:
    """현재 상태와 이번 확인 결과로 다음 행동을 정한다. (행동, 연속실패, 연속정상)"""
    fails = 0 if alive else fails + 1
    oks = oks + 1 if alive else 0
    if not holding and fails >= FAIL_LIMIT:
        return "take", fails, oks
    if holding and oks >= OK_LIMIT:
        return "release", fails, oks
    return "stay", fails, oks


def print_check() -> int:
    f = get_facts()
    print("\n== 지금 이 PC의 값 (매장에서 실행해서 menu-backup.env에 적기) ==")
    print(f"  네트워크 장치 : {f['iface']}")
    print(f"  IP           : {f['ip']}")
    print(f"  공유기       : {f['gateway']}  MAC {f['gateway_mac']}   → GATEWAY_MAC")
    print(f"  Wi-Fi 이름   : {f['ssid'] or '(유선 또는 확인 불가)'}   → STORE_SSID (유선이면 비워 둠)")
    problems = safety_problems(f)
    if not problems:
        print("\n== 점검 통과: 이 PC에서 장애 전환을 켜도 됩니다 ==")
        return 0
    print("\n== 점검 실패: 이 PC에서는 아무것도 바꾸지 않습니다 ==")
    for p in problems:
        print(f"  - {p}")
    return 1


def main() -> None:
    if "--check" in sys.argv:
        sys.exit(print_check())

    holding = vip_here()
    log(f"감시 시작: 주 서버 {MAIN_URL}, VIP {VIP}/{VIP_PREFIX}, 장치 {IFACE}, "
        f"현재 VIP {'보유 중' if holding else '없음'}")

    def stop(*_):
        release_vip()
        log("감시 종료")
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    fails = oks = tick = 0
    safe, last_problems, warned = False, "", False
    facts: dict = {}
    while True:
        if tick % ENV_CHECK_EVERY == 0 or STOP_FILE.exists():  # 비상 정지는 바로 반영
            facts = get_facts()
            problems = safety_problems(facts)
            if problems:
                joined = " / ".join(problems)
                if joined != last_problems:
                    log(f"[대기] 매장 환경이 아님 → 아무것도 바꾸지 않음: {joined}")
                    last_problems = joined
                if holding:
                    release_vip()
                    holding = False
                safe = False
            else:
                if not safe:
                    log(f"매장 환경 확인됨 (공유기 {facts['gateway_mac']}, IP {facts['ip']})")
                    last_problems = ""
                safe = True
                if holding and not gateway_ok(facts["gateway"]):
                    trip("VIP를 가진 동안 공유기 응답이 끊김")
                    holding, safe = False, False
        tick += 1

        if safe:
            alive = main_alive()
            action, fails, oks = decide(holding, alive, fails, oks)
            if action == "take":
                if vip_in_use():
                    if not warned:
                        log("주 서버 메뉴판 응답 없음, 하지만 VIP를 다른 기기가 쓰는 중 → 대기")
                        warned = True
                else:
                    take_vip()
                    time.sleep(1)
                    if gateway_ok(facts["gateway"]):
                        holding, warned = True, False
                        log(f"[전환] 주 서버 {fails}회 연속 응답 없음 → 백업 서버가 VIP {VIP} 가져옴")
                    else:
                        trip("VIP를 붙인 뒤 공유기 응답이 없음")
                        safe = False
            elif action == "release":
                release_vip()
                holding, oks = False, 0
                log(f"[복귀] 주 서버 {OK_LIMIT}회 연속 정상 → VIP {VIP} 반납")
            elif alive:
                warned = False

        time.sleep(CHECK_SEC)


if __name__ == "__main__":
    main()
