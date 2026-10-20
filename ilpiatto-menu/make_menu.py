"""일피아또 메뉴판 생성기 (Python 3.12+)

menu.tsv + sections.tsv + template.html  →  menu/index.html

실행:  python make_menu.py               (기본 출력: menu/index.html)
       python make_menu.py out/menu.html (출력 경로 지정)
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

BASE = Path(__file__).resolve().parent


@dataclass(frozen=True)
class Section:
    id: str
    title_it: str
    title_ko: str
    note: str


@dataclass(frozen=True)
class Item:
    section: str
    name: str
    name_it: str
    category: str
    price: int
    description: str
    option: str


def esc(text: str) -> str:
    """HTML 특수문자 처리 (Java 쪽과 결과가 같도록 직접 구현)."""
    return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;"))


def read_tsv(path: Path) -> list[dict[str, str]]:
    lines = [ln for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    header = lines[0].split("\t")
    rows = []
    for no, line in enumerate(lines[1:], start=2):
        cols = line.split("\t")
        if len(cols) > len(header):
            raise ValueError(f"{path.name} {no}번째 줄: 칸 수가 너무 많습니다")
        cols += [""] * (len(header) - len(cols))
        rows.append({h: c.strip() for h, c in zip(header, cols)})
    return rows


def load() -> tuple[list[Section], list[Item]]:
    sections = [Section(r["id"], r["title_it"], r["title_ko"], r["note"])
                for r in read_tsv(BASE / "sections.tsv")]
    known = {s.id for s in sections}
    items = []
    for r in read_tsv(BASE / "menu.tsv"):
        if r["section"] not in known:
            raise ValueError(f"'{r['name']}'의 섹션 '{r['section']}'이 sections.tsv에 없습니다")
        try:
            price = int(r["price"].replace(",", ""))
        except ValueError:
            raise ValueError(f"'{r['name']}'의 가격 '{r['price']}'이 숫자가 아닙니다") from None
        items.append(Item(r["section"], r["name"], r["name_it"], r["category"],
                          price, r["description"], r["option"]))
    return sections, items


def fmt_price(won: int) -> str:
    """19000 → '19.0', 11900 → '11.9' (천 원 단위 표기)."""
    return f"{won // 1000}.{(won % 1000) // 100}"


def render_item(it: Item) -> str:
    out = [
        '      <li class="item">',
        f'        <div class="row"><h3>{esc(it.name)}</h3><span class="leader"></span>'
        f'<span class="price" aria-label="{it.price:,}원">{fmt_price(it.price)}</span></div>',
        f'        <p class="it" lang="it">{esc(it.name_it)}</p>',
    ]
    if it.description:
        out.append(f'        <p class="desc">{esc(it.description)}</p>')
    if it.option:
        out.append(f'        <span class="opt">{esc(it.option)}</span>')
    out.append("      </li>")
    return "\n".join(out)


def render_section(sec: Section, items: list[Item]) -> str:
    compact = " compact" if all(not i.description for i in items) else ""
    out = [
        f'  <section class="course{compact}" id="{sec.id}" aria-labelledby="h-{sec.id}">',
        '    <header class="course-head">',
        f'      <h2 id="h-{sec.id}" lang="it">{esc(sec.title_it)}</h2>',
        f'      <p class="ko">{esc(sec.title_ko)}</p>',
    ]
    if sec.note:
        out.append(f'      <p class="course-note">{esc(sec.note)}</p>')
    out += ["    </header>", '    <ul class="items">']
    out += [render_item(i) for i in items]
    out += ["    </ul>", "  </section>"]
    return "\n".join(out)


def build() -> tuple[str, int, int]:
    sections, items = load()
    used = [s for s in sections if any(i.section == s.id for i in items)]
    nav = "\n".join(f'    <li><a href="#{s.id}">{esc(s.title_it)}</a></li>' for s in used)
    body = "\n\n".join(render_section(s, [i for i in items if i.section == s.id]) for s in used)
    template = (BASE / "template.html").read_text(encoding="utf-8")
    html = template.replace("{{NAV}}", nav).replace("{{SECTIONS}}", body)
    return html, len(used), len(items)


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else BASE / "menu" / "index.html"
    html, n_sec, n_item = build()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8", newline="\n")
    print(f"메뉴판 생성 완료: {out}  (섹션 {n_sec}개, 메뉴 {n_item}개, {len(html.encode()):,} bytes)")


if __name__ == "__main__":
    main()
