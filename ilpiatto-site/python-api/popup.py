"""홍보 사이트 팝업 API (공지사항 · 신메뉴 출시 예정)

공개(로그인 없음)  GET    /public/popup           ← 홍보 사이트 팝업이 읽음
관리자(로그인)     GET    /api/popup-items        ← 관리자 화면 목록
                   POST   /api/popup-items        ← 추가
                   PUT    /api/popup-items/{id}   ← 수정
                   DELETE /api/popup-items/{id}   ← 삭제

main.py 에 두 줄만 추가하면 된다.
    import popup
    app.include_router(popup.router)
"""
import os
from datetime import date, datetime
from typing import Literal
from zoneinfo import ZoneInfo

import psycopg
from fastapi import APIRouter, HTTPException, Response
from psycopg.rows import dict_row
from pydantic import BaseModel, Field, model_validator

DB_URL = os.getenv(
    "DB_URL", "postgresql://ilpiatto:변경할_비밀번호@127.0.0.1:5432/ilpiatto_db"
)
router = APIRouter()

COLS = ["kind", "title", "body", "launch_date", "image_url",
        "show_from", "show_until", "sort_order", "active"]

# 오늘(한국 시간) 팝업에 보여줄 항목
#  - 사용 중(active)이고, 노출 기간 안에 있는 것
#  - 신메뉴는 출시일로부터 7일이 지나면 자동으로 내려감 ("출시 예정"이 아니므로)
PUBLIC_SQL = """
WITH t AS (SELECT (now() AT TIME ZONE 'Asia/Seoul')::date AS today)
SELECT p.id, p.kind, p.title, p.body, p.launch_date, p.image_url,
       p.show_from, p.show_until, (p.launch_date - t.today) AS d_day
FROM popup_item p, t
WHERE p.active
  AND p.show_from <= t.today
  AND (p.show_until IS NULL OR p.show_until >= t.today)
  AND (p.kind <> 'new_menu' OR p.launch_date >= t.today - 7)
ORDER BY p.sort_order, p.launch_date NULLS LAST, p.id
"""


class PopupItemIn(BaseModel):
    kind: Literal["notice", "new_menu"]
    title: str = Field(min_length=1, max_length=60)
    body: str = Field(default="", max_length=300)
    launch_date: date | None = None
    # 사진은 사이트 안(images/...) 또는 https 주소만 허용 (이상한 주소 차단)
    image_url: str | None = Field(default=None, max_length=300, pattern=r"^(images/|https://)")
    show_from: date | None = None          # 비우면 오늘부터
    show_until: date | None = None         # 비우면 계속
    sort_order: int = Field(default=0, ge=0, le=999)
    active: bool = True

    @model_validator(mode="after")
    def check(self):
        self.title, self.body = self.title.strip(), self.body.strip()
        if self.image_url == "":
            self.image_url = None
        if self.kind == "new_menu" and self.launch_date is None:
            raise ValueError("신메뉴는 출시일을 꼭 입력해야 합니다.")
        if self.show_from and self.show_until and self.show_until < self.show_from:
            raise ValueError("노출 끝 날짜가 시작 날짜보다 빠릅니다.")
        return self


def db():
    return psycopg.connect(DB_URL, row_factory=dict_row)


def params(body: PopupItemIn) -> dict:
    data = body.model_dump()
    data["show_from"] = data["show_from"] or datetime.now(ZoneInfo("Asia/Seoul")).date()  # 한국 날짜
    return data


# ── 공개: 홍보 사이트 팝업 ─────────────────────────────────────
@router.get("/public/popup")
def public_popup(response: Response):
    with db() as conn:
        rows = conn.execute(PUBLIC_SQL).fetchall()
    # 1분만 저장 → 관리자 화면에서 고치면 1분 안에 홈페이지에 반영
    response.headers["Cache-Control"] = "public, max-age=60"
    return {
        "notices": [r for r in rows if r["kind"] == "notice"],
        "new_menus": [r for r in rows if r["kind"] == "new_menu"],
    }


# ── 관리자: 목록·추가·수정·삭제 ────────────────────────────────
@router.get("/api/popup-items")
def list_items():
    with db() as conn:
        rows = conn.execute(
            f"SELECT id, {', '.join(COLS)}, updated_at FROM popup_item ORDER BY active DESC, sort_order, id DESC"
        ).fetchall()
    return {"items": rows}


@router.post("/api/popup-items", status_code=201)
def create_item(body: PopupItemIn):
    cols = ", ".join(COLS)
    vals = ", ".join(f"%({c})s" for c in COLS)
    with db() as conn:
        row = conn.execute(
            f"INSERT INTO popup_item ({cols}) VALUES ({vals}) RETURNING id, {cols}", params(body)
        ).fetchone()
    return row


@router.put("/api/popup-items/{item_id}")
def update_item(item_id: int, body: PopupItemIn):
    sets = ", ".join(f"{c} = %({c})s" for c in COLS)
    with db() as conn:
        row = conn.execute(
            f"UPDATE popup_item SET {sets}, updated_at = now() WHERE id = %(id)s "
            f"RETURNING id, {', '.join(COLS)}",
            {**params(body), "id": item_id},
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="항목을 찾을 수 없습니다.")
    return row


@router.delete("/api/popup-items/{item_id}", status_code=204)
def delete_item(item_id: int):
    with db() as conn:
        deleted = conn.execute("DELETE FROM popup_item WHERE id = %s", (item_id,)).rowcount
    if deleted == 0:
        raise HTTPException(status_code=404, detail="항목을 찾을 수 없습니다.")
    return Response(status_code=204)
