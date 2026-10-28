package com.ilpiatto.api;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 홍보 사이트 팝업 API (공지사항 · 신메뉴 출시 예정) — Python popup.py 와 같은 동작.
 *
 * 공개   GET    /java/public/popup
 * 관리자 GET    /java/api/popup-items      POST /java/api/popup-items
 *        PUT    /java/api/popup-items/{id} DELETE /java/api/popup-items/{id}
 * JSON 이름은 application.properties 의 SNAKE_CASE 설정으로 launch_date 처럼 바뀐다.
 */
@RestController
public class PopupController {

    /** 화면에 보내는 항목. dDay 는 공개 API에서만 채워짐 (출시일 - 오늘) */
    public record PopupItem(Long id, String kind, String title, String body, LocalDate launchDate,
                            String imageUrl, LocalDate showFrom, LocalDate showUntil,
                            Integer sortOrder, Boolean active, Integer dDay) {}

    /** 관리자 화면에서 받는 값 */
    public record PopupItemInput(String kind, String title, String body, LocalDate launchDate,
                                 String imageUrl, LocalDate showFrom, LocalDate showUntil,
                                 Integer sortOrder, Boolean active) {}

    public record PopupView(List<PopupItem> notices, List<PopupItem> newMenus) {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> KINDS = Set.of("notice", "new_menu");
    private static final String COLS =
            "kind, title, body, launch_date, image_url, show_from, show_until, sort_order, active";

    // Python popup.py 의 PUBLIC_SQL 과 같은 조건
    private static final String PUBLIC_SQL = """
            WITH t AS (SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today)
            SELECT p.id, p.kind, p.title, p.body, p.launch_date, p.image_url,
                   p.show_from, p.show_until, p.sort_order, p.active,
                   (p.launch_date - t.today) AS d_day
            FROM popup_item p, t
            WHERE p.active
              AND p.show_from <= t.today
              AND (p.show_until IS NULL OR p.show_until >= t.today)
              AND (p.kind <> 'new_menu' OR p.launch_date >= t.today - 7)
            ORDER BY p.sort_order, p.launch_date NULLS LAST, p.id
            """;

    private final JdbcTemplate jdbc;

    public PopupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 공개: 홍보 사이트 팝업 ─────────────────────────────
    @GetMapping("/public/popup")
    public ResponseEntity<PopupView> publicPopup() {
        List<PopupItem> rows = jdbc.query(PUBLIC_SQL, (rs, n) -> map(rs, true));
        PopupView view = new PopupView(
                rows.stream().filter(r -> r.kind().equals("notice")).toList(),
                rows.stream().filter(r -> r.kind().equals("new_menu")).toList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(view);
    }

    // ── 관리자: 목록·추가·수정·삭제 ────────────────────────
    @GetMapping("/api/popup-items")
    public Map<String, List<PopupItem>> list() {
        return Map.of("items", jdbc.query(
                "SELECT id, " + COLS + " FROM popup_item ORDER BY active DESC, sort_order, id DESC",
                (rs, n) -> map(rs, false)));
    }

    @PostMapping("/api/popup-items")
    @ResponseStatus(HttpStatus.CREATED)
    public PopupItem create(@RequestBody PopupItemInput in) {
        PopupItemInput v = validate(in);
        return jdbc.queryForObject(
                "INSERT INTO popup_item (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, " + COLS,
                (rs, n) -> map(rs, false), args(v));
    }

    @PutMapping("/api/popup-items/{id}")
    public PopupItem update(@PathVariable long id, @RequestBody PopupItemInput in) {
        PopupItemInput v = validate(in);
        Object[] a = args(v);
        Object[] withId = java.util.Arrays.copyOf(a, a.length + 1);
        withId[a.length] = id;
        List<PopupItem> rows = jdbc.query(
                "UPDATE popup_item SET kind = ?, title = ?, body = ?, launch_date = ?, image_url = ?, "
                        + "show_from = ?, show_until = ?, sort_order = ?, active = ?, updated_at = now() "
                        + "WHERE id = ? RETURNING id, " + COLS,
                (rs, n) -> map(rs, false), withId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    @DeleteMapping("/api/popup-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM popup_item WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없습니다.");
        }
    }

    // ── 도우미 ────────────────────────────────────────────
    /** Python 쪽 PopupItemIn 과 같은 검사. 문제가 있으면 400 + 이유 */
    private static PopupItemInput validate(PopupItemInput in) {
        if (in.kind() == null || !KINDS.contains(in.kind())) bad("종류는 notice 또는 new_menu 여야 합니다.");
        String title = in.title() == null ? "" : in.title().strip();
        String body = in.body() == null ? "" : in.body().strip();
        if (title.isEmpty() || title.length() > 60) bad("제목은 1~60자로 입력하세요.");
        if (body.length() > 300) bad("내용은 300자 이하로 입력하세요.");
        if (in.kind().equals("new_menu") && in.launchDate() == null) bad("신메뉴는 출시일을 꼭 입력해야 합니다.");
        String image = in.imageUrl() == null || in.imageUrl().isBlank() ? null : in.imageUrl().strip();
        if (image != null && !(image.startsWith("images/") || image.startsWith("https://"))) {
            bad("사진 주소는 images/ 또는 https:// 로 시작해야 합니다.");
        }
        LocalDate from = in.showFrom() != null ? in.showFrom() : LocalDate.now(KST);
        if (in.showUntil() != null && in.showUntil().isBefore(from)) bad("노출 끝 날짜가 시작 날짜보다 빠릅니다.");
        int order = in.sortOrder() == null ? 0 : in.sortOrder();
        if (order < 0 || order > 999) bad("순서는 0~999 사이로 입력하세요.");
        boolean active = in.active() == null || in.active();
        return new PopupItemInput(in.kind(), title, body, in.launchDate(), image, from, in.showUntil(), order, active);
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static Object[] args(PopupItemInput v) {
        return new Object[] {v.kind(), v.title(), v.body(), sqlDate(v.launchDate()), v.imageUrl(),
                sqlDate(v.showFrom()), sqlDate(v.showUntil()), v.sortOrder(), v.active()};
    }

    private static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static PopupItem map(ResultSet rs, boolean withDDay) throws SQLException {
        Integer dDay = null;
        if (withDDay) {
            int v = rs.getInt("d_day");
            dDay = rs.wasNull() ? null : v;
        }
        return new PopupItem(rs.getLong("id"), rs.getString("kind"), rs.getString("title"), rs.getString("body"),
                localDate(rs, "launch_date"), rs.getString("image_url"), localDate(rs, "show_from"),
                localDate(rs, "show_until"), rs.getInt("sort_order"), rs.getBoolean("active"), dDay);
    }
}
