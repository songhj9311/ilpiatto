"""
일피아토 메뉴판 QR 코드 생성기 (Python 3.12+, qrcode 8.x, Pillow 11+)

설치:  pip install "qrcode[pil]"
실행:  python make_menu_qr.py "https://메뉴판주소" -o menu_qr.png
"""
import argparse

import qrcode
from qrcode.constants import ERROR_CORRECT_H
from PIL import Image, ImageDraw, ImageFont

INK = (30, 43, 35)        # 메뉴판 글자색 #1e2b23
PAPER = (244, 245, 240)   # 메뉴판 배경색 #f4f5f0


def make_qr(url: str, box_size: int = 20, border: int = 4) -> Image.Image:
    """URL을 담은 QR 이미지를 만든다. 오류 복원 H(30%)라 테이블 위에서 긁히거나 젖어도 잘 읽힌다."""
    qr = qrcode.QRCode(
        version=None,                # 데이터 길이에 맞춰 크기 자동 결정
        error_correction=ERROR_CORRECT_H,
        box_size=box_size,           # 모듈(점) 한 칸의 픽셀 크기
        border=border,               # 바깥 여백(조용한 영역), 표준 최소값 4
    )
    qr.add_data(url)
    qr.make(fit=True)
    return qr.make_image(fill_color=INK, back_color=PAPER).convert("RGB")


def add_caption(qr_img: Image.Image, title: str, sub: str, font_path: str | None) -> Image.Image:
    """QR 아래에 'Il Piatto / 메뉴 보기' 같은 안내 문구를 붙인다."""
    w, h = qr_img.size
    band = int(w * 0.22)
    canvas = Image.new("RGB", (w, h + band), PAPER)
    canvas.paste(qr_img, (0, 0))
    draw = ImageDraw.Draw(canvas)

    def font(size):
        if font_path:
            try:
                return ImageFont.truetype(font_path, size)
            except OSError:
                pass
        return ImageFont.load_default(size=size)   # Pillow 10.1+ 지원

    f_title, f_sub = font(int(w * 0.075)), font(int(w * 0.042))
    for text, f, y in ((title, f_title, h - int(w * 0.02)), (sub, f_sub, h + int(band * 0.45))):
        tw = draw.textlength(text, font=f)
        draw.text(((w - tw) / 2, y), text, font=f, fill=INK)
    return canvas


def main():
    p = argparse.ArgumentParser(description="메뉴판 URL로 QR 코드 PNG를 만든다")
    p.add_argument("url", help="QR에 담을 메뉴판 주소")
    p.add_argument("-o", "--out", default="menu_qr.png", help="저장할 파일 이름")
    p.add_argument("--title", default="Il Piatto")
    p.add_argument("--sub", default="QR을 찍으면 메뉴판이 열립니다")
    p.add_argument("--font", default=None, help="한글 폰트 경로 (예: C:/Windows/Fonts/malgun.ttf)")
    p.add_argument("--no-caption", action="store_true", help="QR만 저장")
    args = p.parse_args()

    img = make_qr(args.url)
    if not args.no_caption:
        img = add_caption(img, args.title, args.sub, args.font)
    img.save(args.out, dpi=(300, 300))   # 인쇄용 300dpi
    print(f"저장 완료: {args.out} ({img.size[0]}x{img.size[1]}px)  →  {args.url}")


if __name__ == "__main__":
    main()
