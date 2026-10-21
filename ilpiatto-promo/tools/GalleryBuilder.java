import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 일피아또 갤러리 사진 정리기 (Java 21, 추가 라이브러리 없음) — build_gallery.py 와 같은 동작.
 *
 * 실행: java tools/GalleryBuilder.java [원본폴더] [출력폴더]
 *   (Java 21은 컴파일 없이 .java 파일을 바로 실행할 수 있다)
 * 원본 폴더 구조와 파일 이름 규칙은 build_gallery.py 설명과 같다.
 * 지원 형식: JPG, PNG (WebP·HEIC는 Java 기본 기능으로 못 읽으므로 Python 버전을 쓰거나 JPG로 변환)
 */
public class GalleryBuilder {

    static final Map<String, String> CATEGORIES = Map.of(
            "요리", "food", "공간", "space", "분위기", "mood",
            "food", "food", "space", "space", "mood", "mood");
    static final int FULL_EDGE = 1800, THUMB_EDGE = 800, FEATURE_THUMB_EDGE = 1200;
    // 12장 한 묶음 배치 순서 (PC 4열 5줄, 휴대폰 2열 10줄이 빈칸 없이 채워짐)
    static final List<String> PATTERN = List.of("feature", "tall", "square", "square", "wide", "square",
            "tall", "square", "square", "square", "wide", "wide");

    record Item(Path path, String cat, String shape, String alt) {
        Item withShape(String s) { return new Item(path, cat, s, alt); }
    }

    public static void main(String[] args) throws IOException {
        Path src = Path.of(args.length > 0 ? args[0] : "/mnt/c/ilpiatto/gallery-src");
        Path out = Path.of(args.length > 1 ? args[1] : "images/gallery");
        if (!Files.isDirectory(src)) {
            System.out.println("원본 폴더가 없습니다: " + src);
            System.exit(1);
        }
        System.out.println("원본: " + src + "\n출력: " + out);
        Path thumbs = out.resolve("thumbs");
        Files.createDirectories(thumbs);
        cleanOld(out, thumbs);

        List<Map<String, Object>> photos = new ArrayList<>();
        int n = 0;
        for (Item item : arrange(loadSources(src))) {
            String name = String.format("%02d-%s.jpg", ++n, item.cat());
            BufferedImage img = readUpright(item.path());
            int[] size = save(img, FULL_EDGE, out.resolve(name));
            save(img, item.shape().equals("feature") ? FEATURE_THUMB_EDGE : THUMB_EDGE, thumbs.resolve(name));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("src", "images/gallery/" + name);
            p.put("thumb", "images/gallery/thumbs/" + name);
            p.put("w", size[0]);
            p.put("h", size[1]);
            p.put("alt", item.alt());
            p.put("cat", item.cat());
            p.put("shape", item.shape());
            photos.add(p);
            System.out.printf("  %-16s %-8s %s%n", name, item.shape(), item.alt());
        }
        String json = "{\n  \"generated\": " + q(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
                + ",\n  \"photos\": [\n" + String.join(",\n", photos.stream().map(GalleryBuilder::toJson).toList())
                + "\n  ]\n}\n";
        Files.writeString(out.resolve("gallery.json"), json, StandardCharsets.UTF_8);
        System.out.println("완료: " + photos.size() + "장 → " + out.resolve("gallery.json"));
    }

    // ── 사진 읽기 ────────────────────────────────────────────
    static List<Item> loadSources(Path src) throws IOException {
        List<Item> items = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(src)) {
            for (Path folder : dirs.filter(Files::isDirectory).sorted().toList()) {
                String cat = CATEGORIES.get(folder.getFileName().toString());
                if (cat == null) {
                    System.out.println("  건너뜀(알 수 없는 폴더): " + folder.getFileName() + "  → 요리/공간/분위기 중 하나로");
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : files.sorted().toList()) {
                        String fn = f.getFileName().toString();
                        String ext = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                        if (!List.of("jpg", "jpeg", "png").contains(ext)) {
                            if (List.of("heic", "heif", "webp").contains(ext)) {
                                System.out.println("  건너뜀(" + ext + "): " + fn + "  → JPG로 변환하거나 Python 버전 사용");
                            }
                            continue;
                        }
                        BufferedImage img = readUpright(f);
                        items.add(new Item(f, cat, shapeOf(img.getWidth(), img.getHeight(), fn), altOf(fn)));
                    }
                }
            }
        }
        return items;
    }

    static String shapeOf(int w, int h, String name) {
        if (name.startsWith("대표") || name.startsWith("main")) return "feature";
        double r = (double) w / h;
        if (r >= 1.3) return "wide";
        if (r <= 0.77) return "tall";
        return "square";
    }

    static String altOf(String name) {
        String stem = name.substring(0, name.lastIndexOf('.'));
        stem = stem.replaceFirst("^(대표|main)[-_ ]*", "").replaceFirst("^\\d+[-_ ]*", "");
        stem = stem.replaceAll("[-_]+", " ").strip();
        return stem.isEmpty() ? "일피아또" : stem;
    }

    // ── 배치: PATTERN 순서대로 모양이 맞는 사진, 같은 분류가 연달아 오지 않게 ──
    static List<Item> arrange(List<Item> items) {
        Map<String, List<Item>> pools = new LinkedHashMap<>();
        for (String s : List.of("feature", "wide", "tall", "square")) {
            pools.put(s, new ArrayList<>(items.stream().filter(i -> i.shape().equals(s)).toList()));
        }
        List<Item> placed = new ArrayList<>();
        String lastCat = null;
        while (pools.values().stream().anyMatch(p -> !p.isEmpty())) {
            int before = placed.size();
            for (String shape : PATTERN) {
                List<Item> pool = pools.get(shape);
                if (pool.isEmpty() && shape.equals("square")) pool = pools.get("feature");
                if (pool.isEmpty() && shape.equals("feature")) {
                    List<Item> food = pools.get("square").stream().filter(i -> i.cat().equals("food")).toList();
                    pool = food.isEmpty() ? pools.get("square") : food;
                }
                if (pool.isEmpty()) continue;
                final String lc = lastCat;
                Item pick = pool.stream().filter(i -> !i.cat().equals(lc)).findFirst().orElse(pool.get(0));
                pools.values().forEach(p -> p.remove(pick));
                lastCat = pick.cat();
                placed.add(pick.withShape(shape));
            }
            if (placed.size() == before) break;
        }
        pools.values().forEach(placed::addAll);
        return placed;
    }

    // ── 저장: 크기 줄이기 + JPEG 품질 82 (촬영 정보는 저장하지 않음) ──
    static int[] save(BufferedImage img, int edge, Path dest) throws IOException {
        BufferedImage small = resize(img, edge);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.82f);
        param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        try (ImageOutputStream os = ImageIO.createImageOutputStream(Files.newOutputStream(dest))) {
            writer.setOutput(os);
            writer.write(null, new IIOImage(small, null, null), param);
        } finally {
            writer.dispose();
        }
        return new int[] {small.getWidth(), small.getHeight()};
    }

    /** 반씩 여러 번 줄여서 한 번에 줄일 때보다 깨끗하게 */
    static BufferedImage resize(BufferedImage img, int edge) {
        int w = img.getWidth(), h = img.getHeight();
        double scale = Math.min(1.0, (double) edge / Math.max(w, h));
        int tw = (int) Math.round(w * scale), th = (int) Math.round(h * scale);
        BufferedImage cur = img;
        while (true) {
            int nw = Math.max(tw, cur.getWidth() / 2), nh = Math.max(th, cur.getHeight() / 2);
            BufferedImage next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = next;
            if (nw == tw && nh == th) return cur;
        }
    }

    // ── 휴대폰 사진 회전 바로잡기 (EXIF Orientation 3·6·8) ──
    static BufferedImage readUpright(Path f) throws IOException {
        BufferedImage img = ImageIO.read(f.toFile());
        if (img == null) throw new IOException("읽을 수 없는 사진: " + f);
        int o = exifOrientation(f);
        if (o != 3 && o != 6 && o != 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = o != 3;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        AffineTransform t = new AffineTransform();
        switch (o) {
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { }
        }
        Graphics2D g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    /** JPEG 안의 EXIF에서 방향(0x0112) 값만 읽는다. 없으면 1(정방향). */
    static int exifOrientation(Path f) {
        try (InputStream in = Files.newInputStream(f); DataInputStream d = new DataInputStream(in)) {
            if (d.readUnsignedShort() != 0xFFD8) return 1;               // JPEG 아님
            while (true) {
                int marker = d.readUnsignedShort();
                int len = d.readUnsignedShort() - 2;
                if (marker == 0xFFDA) return 1;                          // 본문 시작 = EXIF 없음
                byte[] seg = d.readNBytes(len);
                if (marker != 0xFFE1 || len < 14 || !new String(seg, 0, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                    continue;
                }
                int base = 6;                                            // "Exif\0\0" 다음이 TIFF 머리
                boolean le = seg[base] == 'I';
                int ifd = base + readInt(seg, base + 4, le);
                int count = readShort(seg, ifd, le);
                for (int i = 0; i < count; i++) {
                    int e = ifd + 2 + i * 12;
                    if (readShort(seg, e, le) == 0x0112) return readShort(seg, e + 8, le);
                }
                return 1;
            }
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    static int readShort(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 : (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    static int readInt(byte[] b, int i, boolean le) {
        return le ? readShort(b, i, true) | readShort(b, i + 2, true) << 16
                  : readShort(b, i, false) << 16 | readShort(b, i + 2, false);
    }

    // ── 기타 ──
    static void cleanOld(Path out, Path thumbs) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(out, "[0-9][0-9]-*.jpg")) {
            for (Path p : s) Files.delete(p);
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(thumbs, "*.jpg")) {
            for (Path p : s) Files.delete(p);
        }
    }

    static String toJson(Map<String, Object> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(q(k) + ": " + (v instanceof Number ? v.toString() : q(v.toString()))));
        return "    {" + String.join(", ", parts) + "}";
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
