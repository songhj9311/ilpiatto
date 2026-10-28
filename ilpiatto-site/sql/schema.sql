-- 일피아토 메뉴 가격 관리 스키마 (PostgreSQL 16)
-- 실행: psql -h 127.0.0.1 -U ilpiatto -d ilpiatto_db -f schema.sql

-- 가게 설정: 항상 1행만 존재
CREATE TABLE IF NOT EXISTS store_config (
    id                  SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    year                INT          NOT NULL DEFAULT 2026,
    taxpayer_type       VARCHAR(10)  NOT NULL DEFAULT 'general'
                        CHECK (taxpayer_type IN ('general', 'simple')),
    annual_sales        NUMERIC(14,0) NOT NULL DEFAULT 0,
    hourly_wage         NUMERIC(10,0) NOT NULL DEFAULT 10320,
    labor_overhead_rate NUMERIC(5,4) NOT NULL DEFAULT 0.20,
    profit_rate         NUMERIC(5,4) NOT NULL DEFAULT 0.20,
    profit_mode         VARCHAR(10)  NOT NULL DEFAULT 'margin'
                        CHECK (profit_mode IN ('margin', 'markup')),
    rounding_unit       INT          NOT NULL DEFAULT 100 CHECK (rounding_unit > 0),
    store_area_m2       NUMERIC(8,2) NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO store_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- 메뉴: 계산 결과(판매가·이윤)는 저장하지 않고 조회할 때마다 계산
CREATE TABLE IF NOT EXISTS menu (
    id              SERIAL PRIMARY KEY,
    name            VARCHAR(50)   NOT NULL UNIQUE,
    ingredient_cost NUMERIC(10,0) NOT NULL CHECK (ingredient_cost >= 0),
    labor_minutes   NUMERIC(6,1)  NOT NULL CHECK (labor_minutes >= 0),
    manual_price    NUMERIC(10,0) CHECK (manual_price > 0),  -- 직접 입력한 판매가(부가세 포함), NULL이면 추천가 사용
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ── 홍보 사이트 팝업 (공지사항 · 신메뉴 출시 예정) ─────────────────────
-- 이 내용을 sql/schema.sql 맨 아래에 붙여 넣으면 배포할 때 자동으로 만들어진다.
-- (IF NOT EXISTS라서 여러 번 실행해도 안전)
CREATE TABLE IF NOT EXISTS popup_item (
    id          SERIAL PRIMARY KEY,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('notice', 'new_menu')),  -- 공지 / 신메뉴
    title       VARCHAR(60)  NOT NULL,                    -- 공지 제목 또는 신메뉴 이름
    body        VARCHAR(300) NOT NULL DEFAULT '',         -- 내용 또는 메뉴 설명
    launch_date DATE,                                     -- 신메뉴 출시일 (신메뉴만 필수)
    image_url   VARCHAR(300),                             -- 사진 주소 (예: images/popup/truffle.jpg)
    show_from   DATE         NOT NULL DEFAULT CURRENT_DATE, -- 이 날부터 팝업에 보임
    show_until  DATE,                                     -- 이 날까지 보임 (비우면 계속)
    sort_order  INTEGER      NOT NULL DEFAULT 0,          -- 작을수록 위에
    active      BOOLEAN      NOT NULL DEFAULT TRUE,       -- 끄면 날짜와 상관없이 숨김
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT popup_new_menu_needs_date CHECK (kind <> 'new_menu' OR launch_date IS NOT NULL),
    CONSTRAINT popup_date_range         CHECK (show_until IS NULL OR show_until >= show_from),
    CONSTRAINT popup_image_url          CHECK (image_url IS NULL OR image_url ~ '^(images/|https://)')
);
CREATE INDEX IF NOT EXISTS popup_item_visible ON popup_item (active, show_from);

-- 앱 계정(ilpiatto)이 읽고 쓸 수 있게
GRANT SELECT, INSERT, UPDATE, DELETE ON popup_item TO ilpiatto;
GRANT USAGE, SELECT ON SEQUENCE popup_item_id_seq TO ilpiatto;
