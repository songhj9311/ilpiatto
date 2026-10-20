package com.ilpiatto.seo;

import java.util.List;

public record StoreInfo(
        String name, String description, String keywords, String phone,
        String street, String locality, String postalCode,
        double lat, double lng, String image, String priceRange, String cuisine,
        List<Hours> hours, List<String> sameAs,
        String naverVerification, String googleVerification, String daumPin) {

    public record Hours(List<String> days, String opens, String closes) {}
}