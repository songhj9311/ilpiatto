import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일피아또 메뉴판 생성기 (Java 21)
 *
 * menu.tsv + sections.tsv + template.html  →  menu/index.html
 *
 * 실행:  java MakeMenu.java               (컴파일 없이 바로 실행, 기본 출력: menu/index.html)
 *        java MakeMenu.java out/menu.html (출력 경로 지정)
 */
public class MakeMenu {

    record Section(String id, String titleIt, String titleKo, String note) {}

    record Item(String section, String name, String nameIt, String category,
                int price, String description, String option) {}

    static final Path BASE = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        Path out = args.length > 0 ? Path.of(args[0]) : BASE.resolve("menu").resolve("index.html");

        List<Section> sections = loadSections();
        List<Item> items = loadItems(sections);
        List<Section> used = sections.stream()
                .filter(s -> items.stream().anyMatch(i -> i.section().equals(s.id())))
                .toList();

        String nav = used.stream()
                .map(s -> "    <li><a href=\"#" + s.id() + "\">" + esc(s.titleIt()) + "</a></li>")
                .collect(Collectors.joining("\n"));
        String body = used.stream()
                .map(s -> renderSection(s, items.stream().filter(i -> i.section().equals(s.id())).toList()))
                .collect(Collectors.joining("\n\n"));

        String template = Files.readString(BASE.resolve("template.html"), StandardCharsets.UTF_8);
        String html = template.replace("{{NAV}}", nav).replace("{{SECTIONS}}", body);

        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.printf("메뉴판 생성 완료: %s  (섹션 %d개, 메뉴 %d개, %,d bytes)%n",
                out, used.size(), items.size(), html.getBytes(StandardCharsets.UTF_8).length);
    }

    // ── 데이터 읽기 ──────────────────────────────────────────

    static List<Map<String, String>> readTsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        String[] header = lines.get(0).split("\t", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            String[] cols = lines.get(n).split("\t", -1);
            if (cols.length > header.length)
                throw new IllegalArgumentException(path.getFileName() + " " + (n + 1) + "번째 줄: 칸 수가 너무 많습니다");
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.length; c++)
                row.put(header[c].strip(), c < cols.length ? cols[c].strip() : "");
            rows.add(row);
        }
        return rows;
    }

    static List<Section> loadSections() throws IOException {
        return readTsv(BASE.resolve("sections.tsv")).stream()
                .map(r -> new Section(r.get("id"), r.get("title_it"), r.get("title_ko"), r.get("note")))
                .toList();
    }

    static List<Item> loadItems(List<Section> sections) throws IOException {
        Set<String> known = sections.stream().map(Section::id).collect(Collectors.toSet());
        List<Item> items = new ArrayList<>();
        for (Map<String, String> r : readTsv(BASE.resolve("menu.tsv"))) {
            if (!known.contains(r.get("section")))
                throw new IllegalArgumentException("'" + r.get("name") + "'의 섹션 '" + r.get("section") + "'이 sections.tsv에 없습니다");
            int price;
            try {
                price = Integer.parseInt(r.get("price").replace(",", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + r.get("name") + "'의 가격 '" + r.get("price") + "'이 숫자가 아닙니다");
            }
            items.add(new Item(r.get("section"), r.get("name"), r.get("name_it"), r.get("category"),
                    price, r.get("description"), r.get("option")));
        }
        return items;
    }

    // ── HTML 만들기 ─────────────────────────────────────────

    /** HTML 특수문자 처리 (Python 쪽과 결과가 같도록 직접 구현). */
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 19000 → "19.0", 11900 → "11.9" (천 원 단위 표기). */
    static String fmtPrice(int won) {
        return (won / 1000) + "." + ((won % 1000) / 100);
    }

    static String renderItem(Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <li class=\"item\">\n");
        sb.append("        <div class=\"row\"><h3>").append(esc(it.name()))
          .append("</h3><span class=\"leader\"></span><span class=\"price\" aria-label=\"")
          .append(String.format(Locale.ROOT, "%,d", it.price())).append("원\">")
          .append(fmtPrice(it.price())).append("</span></div>\n");
        sb.append("        <p class=\"it\" lang=\"it\">").append(esc(it.nameIt())).append("</p>\n");
        if (!it.description().isEmpty())
            sb.append("        <p class=\"desc\">").append(esc(it.description())).append("</p>\n");
        if (!it.option().isEmpty())
            sb.append("        <span class=\"opt\">").append(esc(it.option())).append("</span>\n");
        sb.append("      </li>");
        return sb.toString();
    }

    static String renderSection(Section s, List<Item> items) {
        boolean compact = items.stream().allMatch(i -> i.description().isEmpty());
        StringBuilder sb = new StringBuilder();
        sb.append("  <section class=\"course").append(compact ? " compact" : "")
          .append("\" id=\"").append(s.id()).append("\" aria-labelledby=\"h-").append(s.id()).append("\">\n");
        sb.append("    <header class=\"course-head\">\n");
        sb.append("      <h2 id=\"h-").append(s.id()).append("\" lang=\"it\">").append(esc(s.titleIt())).append("</h2>\n");
        sb.append("      <p class=\"ko\">").append(esc(s.titleKo())).append("</p>\n");
        if (!s.note().isEmpty())
            sb.append("      <p class=\"course-note\">").append(esc(s.note())).append("</p>\n");
        sb.append("    </header>\n");
        sb.append("    <ul class=\"items\">\n");
        sb.append(items.stream().map(MakeMenu::renderItem).collect(Collectors.joining("\n")));
        sb.append("\n    </ul>\n");
        sb.append("  </section>");
        return sb.toString();
    }
}
