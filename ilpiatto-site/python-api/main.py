import logging
import os
import time
from datetime import datetime
from decimal import Decimal
from typing import Literal

import psycopg
from fastapi import FastAPI, HTTPException, Request, Response
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

import pricing

DB_URL = os.getenv(
    "DB_URL", "postgresql://ilpiatto:변경할_비밀번호@127.0.0.1:5432/ilpiatto_db"
)

app = FastAPI(title="일피아또 관리자 API (Python)", root_path="/py")


# ── 로그 기록 ───────────────────────────────────────────────────
# 파일 하나에 계속 이어 쓴다. 일주일마다 log_backup.py가 백업 후 비운다.
# (파일을 직접 나누지 않는 이유: 나누기와 백업을 한곳에서만 하기 위해)
LOG_FILE = os.getenv("LOG_FILE", "/var/log/ilpiatto/python-api.log")
SKIP_PATHS = {"/health"}  # 배포 스크립트의 상태 확인은 기록하지 않음

log = logging.getLogger("ilpiatto")
log.setLevel(logging.INFO)
try:
    _handler = logging.FileHandler(LOG_FILE, mode="a", encoding="utf-8")
except OSError:  # 로그 폴더가 없을 때(개발 PC 등)는 화면에만 출력
    _handler = logging.StreamHandler()
_handler.setFormatter(logging.Formatter(
    "%(asctime)s | %(levelname)-5s | %(message)s", "%Y-%m-%d %H:%M:%S"))
log.addHandler(_handler)


@app.middleware("http")
async def access_log(request: Request, call_next):
    start = time.perf_counter()
    # Nginx 뒤에 있으므로 실제 접속자 IP는 X-Real-IP 헤더에 있음
    ip = request.headers.get("x-real-ip") or (request.client.host if request.client else "-")
    try:
        response = await call_next(request)
    except Exception:
        log.exception("%s | %s %s | 500 | 처리 중 오류", ip, request.method, request.url.path)
        raise
    if request.url.path not in SKIP_PATHS:
        ms = (time.perf_counter() - start) * 1000
        level = logging.WARNING if response.status_code >= 400 else logging.INFO
        log.log(level, "%s | %s %s | %d | %.0fms",
                ip, request.method, request.url.path, response.status_code, ms)
    return response


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
