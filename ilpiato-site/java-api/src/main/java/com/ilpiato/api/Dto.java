package com.ilpiato.api;

import java.math.BigDecimal;
import java.util.List;

/** API에서 주고받는 데이터 형식 모음 */
public final class Dto {

    private Dto() {}

    /** 가게 설정 */
    public record StoreConfig(
        int year,
        String taxpayerType,          // "general" / "simple"
        BigDecimal annualSales,
        BigDecimal hourlyWage,
        BigDecimal laborOverheadRate,
        BigDecimal profitRate,
        String profitMode,            // "margin" / "markup"
        int roundingUnit,
        BigDecimal storeAreaM2
    ) {}

    /** 메뉴 입력 */
    public record MenuInput(
        String name,
        BigDecimal ingredientCost,
        BigDecimal laborMinutes,
        BigDecimal manualPrice        // null이면 추천가 사용
    ) {}

    /** 가격 계산 결과 */
    public record PriceResult(
        long laborCost,
        long totalCost,
        long recommendedPrice,
        long price,
        boolean manual,
        long tax,
        long supplyValue,
        long profit,
        double profitRate,
        List<String> warnings
    ) {}

    /** 메뉴 + 계산 결과 */
    public record MenuView(
        int id,
        String name,
        BigDecimal ingredientCost,
        BigDecimal laborMinutes,
        BigDecimal manualPrice,
        PriceResult pricing
    ) {}

    public record MenuList(List<MenuView> items, List<String> notes) {}

    public record SaveResult(boolean saved, List<String> notes) {}

    public record ErrorBody(String detail) {}
}
