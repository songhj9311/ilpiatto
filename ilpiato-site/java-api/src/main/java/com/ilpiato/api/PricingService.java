package com.ilpiato.api;

import com.ilpiato.api.Dto.MenuInput;
import com.ilpiato.api.Dto.PriceResult;
import com.ilpiato.api.Dto.StoreConfig;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 메뉴 가격 계산과 법령 점검 */
@Service
public class PricingService {

    // ── 법정 기준값 (매년 확인 후 수정) ─────────────────────────────
    static final Map<Integer, BigDecimal> MINIMUM_WAGE =
        Map.of(2026, new BigDecimal("10320"), 2027, new BigDecimal("10700"));
    static final BigDecimal VAT_RATE = new BigDecimal("0.10");
    static final BigDecimal SIMPLE_VALUE_ADDED_RATE = new BigDecimal("0.15");
    static final BigDecimal SIMPLE_TAX_THRESHOLD = new BigDecimal("104000000");
    static final BigDecimal SIMPLE_TAX_EXEMPT = new BigDecimal("48000000");
    static final BigDecimal OUTDOOR_PRICE_AREA = new BigDecimal("150");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal SIXTY = new BigDecimal("60");

    /** 입력값 형식 검사 (Python의 pydantic 검사와 같은 규칙) */
    public void validateConfig(StoreConfig c) {
        require(c.year() >= 2026 && c.year() <= 2100, "연도는 2026~2100 사이여야 합니다.");
        require(Set.of("general", "simple").contains(c.taxpayerType()), "과세 유형은 general 또는 simple입니다.");
        require(Set.of("margin", "markup").contains(c.profitMode()), "이윤 방식은 margin 또는 markup입니다.");
        require(nonNegative(c.annualSales()), "연 매출은 0 이상이어야 합니다.");
        require(c.hourlyWage() != null && c.hourlyWage().signum() > 0, "시급을 입력하세요.");
        require(nonNegative(c.laborOverheadRate()) && c.laborOverheadRate().compareTo(BigDecimal.ONE) <= 0,
            "인건비 가산율은 0~100% 사이여야 합니다.");
        require(nonNegative(c.profitRate()) && c.profitRate().compareTo(new BigDecimal("0.9")) < 0,
            "이윤율은 0~90% 미만이어야 합니다.");
        require(c.roundingUnit() >= 1 && c.roundingUnit() <= 10000, "올림 단위는 1~10,000원입니다.");
        require(nonNegative(c.storeAreaM2()), "영업장 면적은 0 이상이어야 합니다.");
    }

    public void validateMenu(MenuInput m) {
        require(m.name() != null && !m.name().isBlank() && m.name().length() <= 50, "메뉴 이름은 1~50자입니다.");
        require(nonNegative(m.ingredientCost()), "재료비는 0 이상이어야 합니다.");
        require(nonNegative(m.laborMinutes()) && m.laborMinutes().compareTo(new BigDecimal("600")) <= 0,
            "조리 시간은 0~600분입니다.");
        require(m.manualPrice() == null || m.manualPrice().signum() > 0, "직접 입력 판매가는 0보다 커야 합니다.");
    }

    /** 가게 설정 법령 점검. 위반이면 IllegalArgumentException */
    public List<String> checkConfig(StoreConfig c) {
        BigDecimal minWage = MINIMUM_WAGE.get(c.year());
        if (minWage == null) {
            throw new IllegalArgumentException(c.year() + "년 최저임금이 등록되지 않았습니다. PricingService를 갱신하세요.");
        }
        if (c.hourlyWage().compareTo(minWage) < 0) {
            throw new IllegalArgumentException(String.format(
                "[최저임금법 위반] 시급 %,d원은 %d년 최저임금 %,d원보다 낮습니다.",
                c.hourlyWage().longValue(), c.year(), minWage.longValue()));
        }
        List<String> notes = new ArrayList<>();
        if ("simple".equals(c.taxpayerType()) && c.annualSales().compareTo(SIMPLE_TAX_THRESHOLD) >= 0) {
            notes.add("[부가가치세법] 연 매출 1억 400만 원 이상이면 일반과세자로 전환됩니다. 과세 유형을 일반과세로 바꾸세요.");
        }
        if (c.storeAreaM2().compareTo(OUTDOOR_PRICE_AREA) >= 0) {
            notes.add("[식품위생법 시행규칙 별표 17] 영업장 150㎡ 이상: 가게 내부와 외부 모두에 최종지불가격표를 게시해야 합니다.");
        }
        notes.add("[식품위생법 시행규칙 별표 17] 메뉴판에는 '판매가'(부가세 포함)를 표시하고 그 금액만 받아야 합니다. "
            + "가격을 바꾸면 메뉴판과 가격표도 함께 바꾸세요.");
        return notes;
    }

    /** 판매가(부가세 포함) 중 세금으로 나가는 비율 */
    BigDecimal effectiveTaxRate(StoreConfig c) {
        if ("general".equals(c.taxpayerType())) {
            return VAT_RATE.divide(BigDecimal.ONE.add(VAT_RATE), MC);
        }
        if (c.annualSales().compareTo(SIMPLE_TAX_EXEMPT) < 0) {
            return BigDecimal.ZERO;
        }
        return SIMPLE_VALUE_ADDED_RATE.multiply(VAT_RATE);
    }

    public PriceResult calculate(StoreConfig c, BigDecimal ingredientCost,
                                 BigDecimal laborMinutes, BigDecimal manualPrice) {
        BigDecimal hourlyTotal = c.hourlyWage().multiply(BigDecimal.ONE.add(c.laborOverheadRate()));
        BigDecimal laborCost = hourlyTotal.multiply(laborMinutes).divide(SIXTY, MC);
        BigDecimal totalCost = ingredientCost.add(laborCost);

        boolean margin = "margin".equals(c.profitMode());
        BigDecimal targetSupply = margin
            ? totalCost.divide(BigDecimal.ONE.subtract(c.profitRate()), MC)
            : totalCost.multiply(BigDecimal.ONE.add(c.profitRate()));

        BigDecimal taxRate = effectiveTaxRate(c);
        BigDecimal unit = BigDecimal.valueOf(c.roundingUnit());
        BigDecimal rawPrice = targetSupply.divide(BigDecimal.ONE.subtract(taxRate), MC);
        BigDecimal recommended = rawPrice.divide(unit, 0, RoundingMode.CEILING).multiply(unit);

        BigDecimal price = manualPrice != null ? manualPrice : recommended;
        BigDecimal tax = won(price.multiply(taxRate));
        BigDecimal supplyValue = price.subtract(tax);
        BigDecimal profit = supplyValue.subtract(totalCost);
        BigDecimal base = margin ? supplyValue : totalCost;
        BigDecimal actualRate = base.signum() > 0 ? profit.divide(base, MC) : BigDecimal.ZERO;

        List<String> warnings = new ArrayList<>();
        if (profit.signum() < 0) {
            warnings.add("원가보다 낮은 가격입니다 (팔수록 손해)");
        } else if (actualRate.compareTo(c.profitRate()) < 0) {
            warnings.add(String.format("목표 이윤율 %.0f%% 미달", c.profitRate().doubleValue() * 100));
        }

        return new PriceResult(
            won(laborCost).longValue(), won(totalCost).longValue(), recommended.longValue(),
            price.longValue(), manualPrice != null, tax.longValue(), supplyValue.longValue(),
            won(profit).longValue(), actualRate.setScale(4, RoundingMode.HALF_UP).doubleValue(),
            warnings);
    }

    private static BigDecimal won(BigDecimal x) {
        return x.setScale(0, RoundingMode.HALF_UP);
    }

    private static boolean nonNegative(BigDecimal x) {
        return x != null && x.signum() >= 0;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
