import os
from datetime import datetime
from decimal import Decimal
from typing import Literal

import psycopg
from fastapi import FastAPI, HTTPException, Response
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

import pricing

DB_URL = os.getenv(
    "DB_URL", "postgresql://ilpiatto:변경할_비밀번호@127.0.0.1:5432/ilpiatto_db"
)

app = FastAPI(title="일피아또 관리자 API (Python)", root_path="/py")


# ── 요청 데이터 형식 ─────────────────────────────────────────────
class ConfigIn(BaseModel):
    year: int = Field(ge=2026, le=2100)
    taxpayer_type: Literal["general", "simple"]
    annual_sales: Decimal = Field(ge=0)
    hourly_wage: Decimal = Field(gt=0)
    labor_overhead_rate: Decimal = Field(ge=0, le=1)
    profit_rate: Decimal = Field(ge=0, lt=Decimal("0.9"))
    profit_mode: Literal["margin", "markup"]
    rounding_unit: int = Field(ge=1, le=10000)
    store_area_m2: Decimal = Field(ge=0)


class MenuIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    ingredient_cost: Decimal = Field(ge=0)
    labor_minutes: Decimal = Field(ge=0, le=600)
    manual_price: Decimal | None = Field(default=None, gt=0)


CONFIG_COLS = list(ConfigIn.model_fields)


# ── DB 도우미 ───────────────────────────────────────────────────
def db():
    return psycopg.connect(DB_URL, row_factory=dict_row)


def load_config(conn) -> dict:
    return conn.execute("SELECT * FROM store_config WHERE id = 1").fetchone()


def menu_view(row: dict, cfg: dict) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "ingredient_cost": row["ingredient_cost"],
        "labor_minutes": row["labor_minutes"],
        "manual_price": row["manual_price"],
        "pricing": pricing.calculate(
            cfg, row["ingredient_cost"], row["labor_minutes"], row["manual_price"]
        ),
    }


def store_notes(cfg: dict) -> list[str]:
    try:
        return pricing.check_config(cfg)
    except ValueError as e:
        return [str(e)]


# ── API ────────────────────────────────────────────────────────
@app.get("/health")
def health():
    with db() as conn:
        db_time = conn.execute("SELECT now() AS t").fetchone()["t"]
    return {"status": "ok", "server": "python",
            "server_time": datetime.now().isoformat(), "db_time": db_time.isoformat()}


@app.get("/api/config")
def get_config():
    with db() as conn:
        cfg = load_config(conn)
    return {k: cfg[k] for k in CONFIG_COLS}


@app.put("/api/config")
def put_config(body: ConfigIn):
    data = body.model_dump()
    try:
        notes = pricing.check_config(data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    sets = ", ".join(f"{c} = %({c})s" for c in CONFIG_COLS)
    with db() as conn:
        conn.execute(f"UPDATE store_config SET {sets}, updated_at = now() WHERE id = 1", data)
    return {"saved": True, "notes": notes}


@app.get("/api/menus")
def list_menus():
    with db() as conn:
        cfg = load_config(conn)
        rows = conn.execute("SELECT * FROM menu ORDER BY id").fetchall()
    return {"items": [menu_view(r, cfg) for r in rows], "notes": store_notes(cfg)}


@app.post("/api/menus/preview")
def preview_menu(body: MenuIn):
    with db() as conn:
        cfg = load_config(conn)
    return pricing.calculate(cfg, body.ingredient_cost, body.labor_minutes, body.manual_price)


@app.post("/api/menus", status_code=201)
def create_menu(body: MenuIn):
    try:
        with db() as conn:
            row = conn.execute(
                """INSERT INTO menu (name, ingredient_cost, labor_minutes, manual_price)
                   VALUES (%(name)s, %(ingredient_cost)s, %(labor_minutes)s, %(manual_price)s)
                   RETURNING *""",
                body.model_dump(),
            ).fetchone()
            cfg = load_config(conn)
    except psycopg.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="같은 이름의 메뉴가 이미 있습니다.")
    return menu_view(row, cfg)


@app.put("/api/menus/{menu_id}")
def update_menu(menu_id: int, body: MenuIn):
    try:
        with db() as conn:
            row = conn.execute(
                """UPDATE menu SET name = %(name)s, ingredient_cost = %(ingredient_cost)s,
                       labor_minutes = %(labor_minutes)s, manual_price = %(manual_price)s,
                       updated_at = now()
                   WHERE id = %(id)s RETURNING *""",
                {**body.model_dump(), "id": menu_id},
            ).fetchone()
            cfg = load_config(conn)
    except psycopg.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="같은 이름의 메뉴가 이미 있습니다.")
    if row is None:
        raise HTTPException(status_code=404, detail="메뉴를 찾을 수 없습니다.")
    return menu_view(row, cfg)


@app.delete("/api/menus/{menu_id}", status_code=204)
def delete_menu(menu_id: int):
    with db() as conn:
        deleted = conn.execute("DELETE FROM menu WHERE id = %s", (menu_id,)).rowcount
    if deleted == 0:
        raise HTTPException(status_code=404, detail="메뉴를 찾을 수 없습니다.")
    return Response(status_code=204)
