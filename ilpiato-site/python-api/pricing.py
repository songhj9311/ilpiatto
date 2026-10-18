"""메뉴 가격 계산 로직 (웹 API에서 사용)."""
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP

# ── 법정 기준값 (매년 확인 후 수정) ─────────────────────────────
MINIMUM_WAGE = {2026: Decimal("10320"), 2027: Decimal("10700")}  # 최저임금 시급(원)
VAT_RATE = Decimal("0.10")                     # 부가가치세율 10%
SIMPLE_VALUE_ADDED_RATE = Decimal("0.15")      # 간이과세 음식점업 부가가치율 15%
SIMPLE_TAX_THRESHOLD = Decimal("104000000")    # 간이과세 기준 1억 400만 원
SIMPLE_TAX_EXEMPT = Decimal("48000000")        # 간이과세 납부면제 4,800만 원
OUTDOOR_PRICE_AREA = Decimal("150")            # 150㎡ 이상 내·외부 가격표


def D(x) -> Decimal:
    return x if isinstance(x, Decimal) else Decimal(str(x))


def won(x: Decimal) -> int:
    return int(x.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def check_config(cfg: dict) -> list[str]:
    """가게 설정 법령 점검. 위반이면 ValueError, 아니면 안내 문구 목록."""
    min_wage = MINIMUM_WAGE.get(int(cfg["year"]))
    if min_wage is None:
        raise ValueError(f"{cfg['year']}년 최저임금이 등록되지 않았습니다. pricing.py의 MINIMUM_WAGE를 갱신하세요.")
    if D(cfg["hourly_wage"]) < min_wage:
        raise ValueError(
            f"[최저임금법 위반] 시급 {D(cfg['hourly_wage']):,}원은 {cfg['year']}년 최저임금 {min_wage:,}원보다 낮습니다."
        )
    notes = []
    if cfg["taxpayer_type"] == "simple" and D(cfg["annual_sales"]) >= SIMPLE_TAX_THRESHOLD:
        notes.append("[부가가치세법] 연 매출 1억 400만 원 이상이면 일반과세자로 전환됩니다. 과세 유형을 일반과세로 바꾸세요.")
    if D(cfg["store_area_m2"]) >= OUTDOOR_PRICE_AREA:
        notes.append("[식품위생법 시행규칙 별표 17] 영업장 150㎡ 이상: 가게 내부와 외부 모두에 최종지불가격표를 게시해야 합니다.")
    notes.append("[식품위생법 시행규칙 별표 17] 메뉴판에는 '판매가'(부가세 포함)를 표시하고 그 금액만 받아야 합니다. "
                 "가격을 바꾸면 메뉴판과 가격표도 함께 바꾸세요.")
    return notes


def effective_tax_rate(cfg: dict) -> Decimal:
    """판매가(부가세 포함) 중 세금으로 나가는 비율."""
    if cfg["taxpayer_type"] == "general":
        return VAT_RATE / (1 + VAT_RATE)
    if D(cfg["annual_sales"]) < SIMPLE_TAX_EXEMPT:
        return Decimal("0")
    return SIMPLE_VALUE_ADDED_RATE * VAT_RATE


def calculate(cfg: dict, ingredient_cost, labor_minutes, manual_price=None) -> dict:
    """원가 → 추천 판매가 계산. manual_price가 있으면 그 가격 기준으로 이윤을 계산."""
    profit_rate = D(cfg["profit_rate"])
    unit = D(cfg["rounding_unit"])

    hourly_total = D(cfg["hourly_wage"]) * (1 + D(cfg["labor_overhead_rate"]))
    labor_cost = hourly_total * D(labor_minutes) / 60
    total_cost = D(ingredient_cost) + labor_cost

    if cfg["profit_mode"] == "margin":
        target_supply = total_cost / (1 - profit_rate)
    else:
        target_supply = total_cost * (1 + profit_rate)

    tax_rate = effective_tax_rate(cfg)
    raw_price = target_supply / (1 - tax_rate)
    recommended = (raw_price / unit).to_integral_value(ROUND_CEILING) * unit

    price = D(manual_price) if manual_price else recommended
    tax = D(won(price * tax_rate))
    supply_value = price - tax
    profit = supply_value - total_cost
    base = supply_value if cfg["profit_mode"] == "margin" else total_cost
    actual_rate = profit / base if base > 0 else Decimal("0")

    warnings = []
    if profit < 0:
        warnings.append("원가보다 낮은 가격입니다 (팔수록 손해)")
    elif actual_rate < profit_rate:
        warnings.append(f"목표 이윤율 {profit_rate:.0%} 미달")

    return {
        "labor_cost": won(labor_cost),
        "total_cost": won(total_cost),
        "recommended_price": int(recommended),
        "price": int(price),
        "manual": bool(manual_price),
        "tax": won(tax),
        "supply_value": won(supply_value),
        "profit": won(profit),
        "profit_rate": float(round(actual_rate, 4)),
        "warnings": warnings,
    }
