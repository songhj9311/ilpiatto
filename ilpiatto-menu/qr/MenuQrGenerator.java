/*
 * 일피아토 메뉴판 QR 코드 생성기 (Java 21, ZXing 3.5.3)
 *
 * Gradle:  implementation("com.google.zxing:core:3.5.3")
 *          implementation("com.google.zxing:javase:3.5.3")
 * Maven:   com.google.zxing:core:3.5.3, com.google.zxing:javase:3.5.3
 *
 * 실행:    java MenuQrGenerator "https://메뉴판주소" menu_qr.png
 */
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class MenuQrGenerator {

    private static final int INK   = 0xFF1E2B23; // 메뉴판 글자색 (ARGB)
    private static final int PAPER = 0xFFF4F5F0; // 메뉴판 배경색 (ARGB)

    /** URL을 QR PNG 파일로 저장한다. */
    public static void generate(String url, Path out, int sizePx) throws WriterException, IOException {
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, // 30% 손상까지 복원
                EncodeHintType.CHARACTER_SET, "UTF-8",                   // 한글 주소 대비
                EncodeHintType.MARGIN, 4                                  // 바깥 여백(표준 최소 4칸)
        );
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        MatrixToImageWriter.writeToPath(matrix, "PNG", out, new MatrixToImageConfig(INK, PAPER));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("사용법: java MenuQrGenerator <메뉴판URL> [저장파일.png] [크기px]");
            System.exit(1);
        }
        String url  = args[0];
        Path   out  = Path.of(args.length > 1 ? args[1] : "menu_qr.png");
        int    size = args.length > 2 ? Integer.parseInt(args[2]) : 1000;

        generate(url, out, size);
        System.out.printf("저장 완료: %s (%dx%dpx) -> %s%n", out, size, size, url);
    }
}
