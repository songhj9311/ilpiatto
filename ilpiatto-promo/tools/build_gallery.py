#!/usr/bin/env python3
"""일피아또 갤러리 사진 정리기 (Python 3.12 + Pillow)

원본 사진을 분류 폴더에 넣고 실행하면,
  1. 휴대폰 사진의 회전을 바로잡고 위치정보(GPS) 등 촬영 정보를 지운다
  2. 웹용 크기(큰 사진 1800px, 목록용 800px)로 줄여 JPEG로 저장한다
  3. 사진 모양(가로/세로/정사각)을 보고 갤러리 배치 순서를 정한다
  4. images/gallery/gallery.json 을 만든다 (홍보 사이트가 이 파일을 읽음)

원본 폴더 구조 (기본: C:\\ilpiatto\\gallery-src)
  gallery-src/
    요리/    대표_트러플크림뇨끼.jpg   ← 파일 이름이 '대표'로 시작하면 큰 칸(2×2)
    공간/    홀-전경.jpg
    분위기/  저녁-창가.jpg
  파일 이름이 사진 설명(alt)이 된다. '-'와 '_'는 띄어쓰기로 바뀐다.

실행:  python3 tools/build_gallery.py [원본폴더] [출력폴더]
설치:  pip install --break-system-packages pillow
"""
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageOps

HERE = Path(__file__).resolve().parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/mnt/d/ilpiatto/gallery-src")
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else "ilpiatto-promo"/HERE.parent / "images" / "gallery"

CATEGORIES = {"요리": "food", "공간": "space", "분위기": "mood",
              "food": "food", "space": "space", "mood": "mood"}
EXTS = {".jpg", ".jpeg", ".png", ".webp"}
FULL_EDGE, THUMB_EDGE, FEATURE_THUMB_EDGE = 1800, 800, 1200

# 12장 한 묶음의 배치 순서. 4열(PC)에서는 5줄, 2열(휴대폰)에서는 10줄이 빈칸 없이 꽉 찬다.
#   PC:  [대 대 세 정]   휴대폰: [대 대]
#        [대 대 세 정]          [대 대]
#        [가 가 정 세]          [세 정] ...
#        [정 정 정 세]
#        [가 가 가 가]
PATTERN = ["feature", "tall", "square", "square", "wide", "square",
           "tall", "square", "square", "square", "wide", "wide"]


def shape_of(w: int, h: int, name: str) -> str:
    if name.startswith(("대표", "main")):
        return "feature"
    r = w / h
    if r >= 1.3:
        return "wide"
    if r <= 0.77:
        return "tall"
    return "square"


def alt_of(name: str) -> str:
    stem = re.sub(r"^(대표|main)[-_ ]*", "", Path(name).stem)
    stem = re.sub(r"^\d+[-_ ]*", "", stem)          # 앞 번호 제거 (01-...)
    return re.sub(r"[-_]+", " ", stem).strip() or "일피아토"


def load_sources() -> list[dict]:
    items = []
    for folder in sorted(p for p in SRC.iterdir() if p.is_dir()):
        cat = CATEGORIES.get(folder.name)
        if not cat:
            print(f"  건너뜀(알 수 없는 폴더): {folder.name}  → 요리/공간/분위기 중 하나로")
            continue
        for f in sorted(folder.iterdir()):
            if f.suffix.lower() in {".heic", ".heif"}:
                print(f"  건너뜀(HEIC): {f.name}  → 아이폰 설정 > 카메라 > 포맷 > '높은 호환성'으로 찍거나 JPG로 변환")
                continue
            if f.suffix.lower() not in EXTS:
                continue
            with Image.open(f) as im:
                im = ImageOps.exif_transpose(im)      # 휴대폰 회전 정보 반영
                w, h = im.size
            items.append({"path": f, "cat": cat, "shape": shape_of(w, h, f.name), "alt": alt_of(f.name)})
    return items


def arrange(items: list[dict]) -> list[dict]:
    """PATTERN 순서대로 모양이 맞는 사진을 고른다. 같은 분류가 연달아 오지 않게 섞는다."""
    pools = {s: [i for i in items if i["shape"] == s] for s in ("feature", "wide", "tall", "square")}
    placed, last_cat = [], None

    def take(shape: str):
        nonlocal last_cat
        pool = pools[shape]
        if not pool and shape == "square" and pools["feature"]:
            pool = pools["feature"]                    # 정사각 칸은 '대표' 사진으로도 채울 수 있음
        if not pool and shape == "feature":
            pool = [i for i in pools["square"] if i["cat"] == "food"] or pools["square"]
        if not pool:
            return None
        pick = next((i for i in pool if i["cat"] != last_cat), pool[0])
        for p in pools.values():
            if pick in p:
                p.remove(pick)
        last_cat = pick["cat"]
        return {**pick, "shape": shape}

    while any(pools.values()):
        before = len(placed)
        for shape in PATTERN:
            got = take(shape)
            if got:
                placed.append(got)
        if len(placed) == before:                       # 더 이상 맞는 칸이 없으면 남은 사진은 뒤에
            break
    for p in pools.values():
        placed += p
    return placed


def save(im: Image.Image, edge: int, dest: Path) -> tuple[int, int]:
    im = im.copy()
    im.thumbnail((edge, edge), Image.Resampling.LANCZOS)
    # exif 를 넘기지 않으므로 GPS·기기 정보가 저장되지 않는다
    im.save(dest, "JPEG", quality=82, optimize=True, progressive=True)
    return im.size


def main() -> int:
    if not SRC.is_dir():
        print(f"원본 폴더가 없습니다: {SRC}")
        return 1
    print(f"원본: {SRC}\n출력: {OUT}")
    (OUT / "thumbs").mkdir(parents=True, exist_ok=True)
    for old in list(OUT.glob("[0-9][0-9]-*.jpg")) + list((OUT / "thumbs").glob("*.jpg")):
        old.unlink()                                    # 이전에 만든 파일 정리

    photos = []
    for n, item in enumerate(arrange(load_sources()), start=1):
        name = f"{n:02d}-{item['cat']}.jpg"
        with Image.open(item["path"]) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            w, h = save(im, FULL_EDGE, OUT / name)
            edge = FEATURE_THUMB_EDGE if item["shape"] == "feature" else THUMB_EDGE
            save(im, edge, OUT / "thumbs" / name)
        photos.append({"src": f"images/gallery/{name}", "thumb": f"images/gallery/thumbs/{name}",
                       "w": w, "h": h, "alt": item["alt"], "cat": item["cat"], "shape": item["shape"]})
        print(f"  {name:16} {item['shape']:8} {item['alt']}")

    data = {"generated": datetime.now().isoformat(timespec="seconds"), "photos": photos}
    (OUT / "gallery.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"완료: {len(photos)}장 → {OUT / 'gallery.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
