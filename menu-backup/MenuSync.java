/*
 * 일피아토 메뉴판 동기화 (백업 서버에서 5분마다 실행) — Java 21, 외부 라이브러리 없음
 *
 * 주 서버의 메뉴판을 받아 와서, 정상 파일이고 내용이 바뀌었을 때만 백업 서버에 반영한다.
 * 주 서버가 꺼져 있으면 아무것도 바꾸지 않고 마지막 정상본을 그대로 둔다.
 *
 * 실행: java MenuSync.java
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public class MenuSync {

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    static final String MAIN_URL     = env("MAIN_URL", "http://172.30.1.14:8090/menu/");
    static final Path   WEB_DIR      = Path.of(env("WEB_DIR", "/var/www/ilpiatto/menu"));
    static final Path   ARCHIVE_DIR  = Path.of(env("ARCHIVE_DIR", "/var/backups/ilpiatto-menu"));
    static final int    KEEP_VERSIONS = Integer.parseInt(env("KEEP_VERSIONS", "20"));
    static final String MUST_CONTAIN = env("MUST_CONTAIN", "Il Piatto");
    static final int    MIN_BYTES    = 1024;

    static void log(String msg) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " | " + msg);
    }

    static byte[] fetch(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "ilpiatto-menu-sync")
                .header("Cache-Control", "no-cache")
                .build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    /** 정상 메뉴판이면 null, 아니면 이유를 돌려준다. */
    static String invalidReason(byte[] body) {
        if (body.length < MIN_BYTES) return "파일이 너무 작음 (" + body.length + " bytes)";
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.contains(MUST_CONTAIN)) return "'" + MUST_CONTAIN + "' 글자가 없음 (오류 페이지일 가능성)";
        return null;
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** 임시 파일에 다 쓴 뒤 한 번에 바꿔치기 → 손님이 반쯤 쓰인 파일을 볼 일이 없다. */
    static void writeAtomic(Path target, byte[] data) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Path archive(byte[] data) throws Exception {
        Files.createDirectories(ARCHIVE_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path path = ARCHIVE_DIR.resolve("menu_" + stamp + ".html");
        Files.write(path, data);

        if (KEEP_VERSIONS > 0) {
            List<Path> versions;
            try (Stream<Path> s = Files.list(ARCHIVE_DIR)) {
                versions = s.filter(p -> p.getFileName().toString().matches("menu_\\d{8}_\\d{6}\\.html"))
                            .sorted().toList();
            }
            for (int i = 0; i < versions.size() - KEEP_VERSIONS; i++) Files.deleteIfExists(versions.get(i));
        }
        return path;
    }

    public static void main(String[] args) throws Exception {
        Path target = WEB_DIR.resolve("index.html");
        byte[] body;
        try {
            body = fetch(MAIN_URL);
        } catch (Exception e) {
            log("주 서버 응답 없음 → 기존 메뉴판 유지 (" + e + ")");
            return; // 주 서버 장애는 동기화 실패가 아님
        }

        String reason = invalidReason(body);
        if (reason != null) {
            log("받은 파일이 정상 메뉴판이 아님 → 반영 안 함: " + reason);
            System.exit(1);
        }

        if (Files.exists(target) && sha256(Files.readAllBytes(target)).equals(sha256(body))) {
            log("변경 없음");
            return;
        }

        writeAtomic(target, body);
        Path saved = archive(body);
        log(String.format("메뉴판 갱신 완료 (%,d bytes), 버전 보관: %s", body.length, saved.getFileName()));
    }
}
