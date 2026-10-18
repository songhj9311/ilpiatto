-- 일피아토 메뉴 가격 관리 스키마 (PostgreSQL 16)
-- 실행: psql -h 127.0.0.1 -U ilpiato -d ilpiato_db -f schema.sql

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
