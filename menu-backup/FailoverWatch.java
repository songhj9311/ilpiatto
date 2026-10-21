/*
 * 일피아토 메뉴판 장애 전환 감시 (백업 서버에서 항상 실행, root 권한 필요) — Java 21
 *
 * QR 주소의 가상 IP(VIP)를 평소에는 주 서버(Windows)가 갖고 있다.
 *  - 주 서버 메뉴판이 FAIL_LIMIT번 연속 응답 없음 + VIP를 아무도 안 쓰고 있음 → 백업 서버가 VIP를 가져옴
 *  - 백업이 VIP를 가진 상태에서 주 서버가 OK_LIMIT번 연속 정상 → VIP를 돌려줌
 *
 * 안전장치 (하나라도 안 맞으면 네트워크를 전혀 건드리지 않고 대기)
 *  - 이 PC의 IP가 매장 Wi-Fi 대역(WIFI_SUBNET) 안에 있을 것
 *  - 공유기(기본 게이트웨이) MAC이 GATEWAY_MAC과 같을 것
 *  - STORE_SSID를 적었다면 연결된 Wi-Fi 이름이 같을 것 (유선이면 비워 둠)
 *  - 비상 정지 파일(STOP_FILE)이 없을 것
 *  VIP를 붙인 뒤 공유기 응답이 끊기면 즉시 되돌리고 STOP 파일을 만들어 멈춘다.
 *
 * 실행: java FailoverWatch.java            감시
 *       java FailoverWatch.java --check    현재 값과 점검 결과만 출력 (아무것도 안 바꿈)
 */
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FailoverWatch {

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    static final String MAIN_URL     = env("MAIN_URL", "http://172.30.1.14:8090/menu/");
    static final String WIFI_SUBNET  = env("WIFI_SUBNET", "172.30.1.0/24");
    static final String VIP          = env("VIP", "172.30.1.200");
    static final String VIP_PREFIX   = env("VIP_PREFIX", "24");
    static final String GATEWAY_MAC  = env("GATEWAY_MAC", "");
    static final String STORE_SSID   = env("STORE_SSID", "");
    static final Path   STOP_FILE    = Path.of(env("STOP_FILE", "/etc/ilpiatto/menu-backup.STOP"));
    static final long   CHECK_MS     = (long) (Double.parseDouble(env("CHECK_SEC", "3")) * 1000);
    static final int    FAIL_LIMIT   = Integer.parseInt(env("FAIL_LIMIT", "3"));
    static final int    OK_LIMIT     = Integer.parseInt(env("OK_LIMIT", "10"));
    static final int    ENV_CHECK_EVERY = 20; // 감시 20번(약 1분)마다 매장 환경 재확인
    static final String MUST_CONTAIN = env("MUST_CONTAIN", "Il Piatto");

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static String iface;

    record Result(int code, String out, String err) {}
    record Decision(String action, int fails, int oks) {}
    record Facts(String iface, String ip, String gateway, String gatewayMac, String ssid) {}

    static void log(String msg) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " | " + msg);
    }

    static Result run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(p.waitFor(), out, err);
        } catch (Exception e) {
            return new Result(127, "", e.toString());
        }
    }

    static List<String> words(String s) { return Arrays.asList(s.trim().split("\\s+")); }

    static String after(List<String> w, String key) {
        int i = w.indexOf(key);
        return (i >= 0 && i + 1 < w.size()) ? w.get(i + 1) : "";
    }

    static String normMac(String mac) { return mac.trim().toUpperCase().replace('-', ':').replace('.', ':'); }

    static boolean commandExists(String name) { return run("sh", "-c", "command -v " + name).code() == 0; }

    /** 주 서버로 가는 경로의 네트워크 장치 이름 (예: wlp2s0, enp3s0). */
    static String detectIface() {
        String fixed = env("IFACE", "");
        if (!fixed.isEmpty()) return fixed;
        return after(words(run("ip", "-o", "route", "get", URI.create(MAIN_URL).getHost()).out()), "dev");
    }

    // ── 매장 환경 확인 ────────────────────────────────────────────────
    static Facts getFacts() {
        String ip = "";
        for (String line : run("ip", "-4", "-o", "addr", "show", "dev", iface).out().split("\n")) {
            List<String> w = words(line);
            if (w.size() > 3) {
                String addr = w.get(3).split("/")[0];
                if (!addr.equals(VIP)) { ip = addr; break; }
            }
        }
        String gw = after(words(run("ip", "-4", "route", "show", "default", "dev", iface).out()), "via");
        String mac = "";
        if (!gw.isEmpty()) {
            run("ping", "-c", "1", "-W", "1", gw); // ARP 표 채우기
            String m = after(words(run("ip", "neigh", "show", gw, "dev", iface).out()), "lladdr");
            if (!m.isEmpty()) mac = normMac(m);
        }
        String ssid = "";
        if (commandExists("iwgetid")) {
            ssid = run("iwgetid", "-r").out().trim();
        } else if (commandExists("nmcli")) {
            for (String line : run("nmcli", "-t", "-f", "active,ssid", "dev", "wifi").out().split("\n"))
                if (line.startsWith("yes:")) ssid = line.substring(4);
        }
        return new Facts(iface, ip, gw, mac, ssid);
    }

    static boolean inSubnet(String ip, String cidr) {
        if (ip == null || ip.isBlank()) return false; // 빈 문자열은 InetAddress가 localhost로 해석함
        try {
            String[] parts = cidr.split("/");
            int bits = Integer.parseInt(parts[1]);
            byte[] a = InetAddress.getByName(ip).getAddress();
            byte[] n = InetAddress.getByName(parts[0]).getAddress();
            if (a.length != 4 || n.length != 4) return false;
            long ai = ((a[0] & 0xFFL) << 24) | ((a[1] & 0xFFL) << 16) | ((a[2] & 0xFFL) << 8) | (a[3] & 0xFFL);
            long ni = ((n[0] & 0xFFL) << 24) | ((n[1] & 0xFFL) << 16) | ((n[2] & 0xFFL) << 8) | (n[3] & 0xFFL);
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (ai & mask) == (ni & mask);
        } catch (Exception e) {
            return false;
        }
    }

    /** 통과하면 빈 목록, 안 맞으면 이유 목록. */
    static List<String> safetyProblems(Facts f) {
        List<String> p = new ArrayList<>();
        if (Files.exists(STOP_FILE)) {
            String why = "";
            try { why = Files.readString(STOP_FILE).trim(); } catch (Exception ignored) {}
            p.add("비상 정지 파일이 있음: " + STOP_FILE + " (" + why + ")");
        }
        if (f.iface().isEmpty()) p.add("주 서버로 가는 네트워크 장치를 찾지 못함");
        if (GATEWAY_MAC.isEmpty()) p.add("GATEWAY_MAC이 비어 있음 → 매장에서 --check 결과의 공유기 MAC을 menu-backup.env에 적기");
        else if (!f.gatewayMac().equals(normMac(GATEWAY_MAC)))
            p.add("공유기가 다름: 지금 MAC '" + f.gatewayMac() + "', 매장 '" + normMac(GATEWAY_MAC) + "'");
        if (!STORE_SSID.isEmpty() && !f.ssid().equals(STORE_SSID))
            p.add("Wi-Fi가 다름: 지금 '" + f.ssid() + "', 매장 '" + STORE_SSID + "'");
        if (!inSubnet(f.ip(), WIFI_SUBNET)) p.add("이 PC의 IP '" + f.ip() + "'가 매장 대역 " + WIFI_SUBNET + " 밖");
        if (f.gateway().isEmpty()) p.add("기본 게이트웨이(공유기)가 없음");
        return p;
    }

    static boolean gatewayOk(String gw) {
        return !gw.isEmpty() && run("ping", "-c", "2", "-W", "1", gw).code() == 0;
    }

    // ── 주 서버·VIP ─────────────────────────────────────────────────
    static boolean mainAlive() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(MAIN_URL))
                    .timeout(Duration.ofSeconds(2)).header("User-Agent", "ilpiatto-failover").build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().contains(MUST_CONTAIN);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean vipHere() {
        return run("ip", "-4", "-o", "addr", "show", "dev", iface).out().contains(" " + VIP + "/");
    }

    /** 중복 주소 검사(DAD). arping -D는 아무도 응답하지 않을 때 0을 돌려준다. */
    static boolean vipInUse() {
        return run("arping", "-D", "-q", "-c", "2", "-w", "3", "-I", iface, VIP).code() != 0;
    }

    static void takeVip() {
        Result r = run("ip", "addr", "add", VIP + "/" + VIP_PREFIX, "dev", iface);
        if (r.code() != 0 && !r.err().contains("File exists")) throw new IllegalStateException(r.err().trim());
        run("arping", "-U", "-c", "3", "-I", iface, "-s", VIP, VIP); // 주변 기기 ARP 기록 갱신
    }

    static void releaseVip() {
        if (vipHere()) {
            run("ip", "addr", "del", VIP + "/" + VIP_PREFIX, "dev", iface);
            log("VIP " + VIP + " 내려놓음");
        }
    }

    /** 비상 정지: VIP를 되돌리고 STOP 파일을 남김 (재부팅·재시작해도 다시 안 켜짐). */
    static void trip(String reason) {
        releaseVip();
        try {
            Files.createDirectories(STOP_FILE.getParent());
            Files.writeString(STOP_FILE, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + reason + "\n");
        } catch (Exception e) {
            log("STOP 파일을 만들지 못함: " + e);
        }
        log("[비상 정지] " + reason + " → VIP 되돌리고 멈춤. 원인 확인 후 'sudo rm " + STOP_FILE + "' 하면 다시 동작");
    }

    /** 현재 상태와 이번 확인 결과로 다음 행동을 정한다. */
    static Decision decide(boolean holding, boolean alive, int fails, int oks) {
        fails = alive ? 0 : fails + 1;
        oks = alive ? oks + 1 : 0;
        if (!holding && fails >= FAIL_LIMIT) return new Decision("take", fails, oks);
        if (holding && oks >= OK_LIMIT) return new Decision("release", fails, oks);
        return new Decision("stay", fails, oks);
    }

    static int printCheck() {
        Facts f = getFacts();
        System.out.println("\n== 지금 이 PC의 값 (매장에서 실행해서 menu-backup.env에 적기) ==");
        System.out.println("  네트워크 장치 : " + f.iface());
        System.out.println("  IP           : " + f.ip());
        System.out.println("  공유기       : " + f.gateway() + "  MAC " + f.gatewayMac() + "   → GATEWAY_MAC");
        System.out.println("  Wi-Fi 이름   : " + (f.ssid().isEmpty() ? "(유선 또는 확인 불가)" : f.ssid()) + "   → STORE_SSID (유선이면 비워 둠)");
        List<String> problems = safetyProblems(f);
        if (problems.isEmpty()) {
            System.out.println("\n== 점검 통과: 이 PC에서 장애 전환을 켜도 됩니다 ==");
            return 0;
        }
        System.out.println("\n== 점검 실패: 이 PC에서는 아무것도 바꾸지 않습니다 ==");
        problems.forEach(p -> System.out.println("  - " + p));
        return 1;
    }

    public static void main(String[] args) throws Exception {
        iface = detectIface();
        if (Arrays.asList(args).contains("--check")) System.exit(printCheck());

        boolean holding = vipHere();
        log("감시 시작: 주 서버 " + MAIN_URL + ", VIP " + VIP + "/" + VIP_PREFIX + ", 장치 " + iface
                + ", 현재 VIP " + (holding ? "보유 중" : "없음"));

        // systemctl stop 할 때 VIP를 쥔 채로 끝나지 않게
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { releaseVip(); log("감시 종료"); }));

        int fails = 0, oks = 0, tick = 0;
        boolean safe = false, warned = false;
        String lastProblems = "";
        Facts facts = null;
        while (true) {
            if (tick % ENV_CHECK_EVERY == 0 || Files.exists(STOP_FILE)) { // 비상 정지는 바로 반영
                facts = getFacts();
                List<String> problems = safetyProblems(facts);
                if (!problems.isEmpty()) {
                    String joined = String.join(" / ", problems);
                    if (!joined.equals(lastProblems)) {
                        log("[대기] 매장 환경이 아님 → 아무것도 바꾸지 않음: " + joined);
                        lastProblems = joined;
                    }
                    if (holding) { releaseVip(); holding = false; }
                    safe = false;
                } else {
                    if (!safe) {
                        log("매장 환경 확인됨 (공유기 " + facts.gatewayMac() + ", IP " + facts.ip() + ")");
                        lastProblems = "";
                    }
                    safe = true;
                    if (holding && !gatewayOk(facts.gateway())) {
                        trip("VIP를 가진 동안 공유기 응답이 끊김");
                        holding = false;
                        safe = false;
                    }
                }
            }
            tick++;

            if (safe) {
                boolean alive = mainAlive();
                Decision d = decide(holding, alive, fails, oks);
                fails = d.fails();
                oks = d.oks();
                switch (d.action()) {
                    case "take" -> {
                        if (vipInUse()) {
                            if (!warned) { log("주 서버 메뉴판 응답 없음, 하지만 VIP를 다른 기기가 쓰는 중 → 대기"); warned = true; }
                        } else {
                            takeVip();
                            Thread.sleep(1000);
                            if (gatewayOk(facts.gateway())) {
                                holding = true;
                                warned = false;
                                log("[전환] 주 서버 " + fails + "회 연속 응답 없음 → 백업 서버가 VIP " + VIP + " 가져옴");
                            } else {
                                trip("VIP를 붙인 뒤 공유기 응답이 없음");
                                safe = false;
                            }
                        }
                    }
                    case "release" -> {
                        releaseVip();
                        holding = false;
                        oks = 0;
                        log("[복귀] 주 서버 " + OK_LIMIT + "회 연속 정상 → VIP " + VIP + " 반납");
                    }
                    default -> { if (alive) warned = false; }
                }
            }
            Thread.sleep(CHECK_MS);
        }
    }
}
