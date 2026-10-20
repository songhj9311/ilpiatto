package com.ilpiatto.seo;

import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class SeoController {

    private static final String SITE_URL = "https://www.example.com";

    private final ObjectMapper objectMapper;

    private final StoreInfo store = new StoreInfo(
            "일피아토",
            "산본동 이탈리안 레스토랑 일피아토 - 생면 파스타와 함박 스테이크",
            "일피아또, 산본동 맛집, 파스타, 이탈리안 레스토랑",
            "+82-031-396-8111", "수리산로 191-1", "경기도 군포시", "00000",
            37.3650, 126.9210,
            SITE_URL + "/og-image.jpg", "₩₩", "Italian",
            List.of(
                    new StoreInfo.Hours(List.of("Monday","Tuesday","Wednesday","Thursday","Friday", "Saturday", "Sunday"), "11:00", "21:30")),
            List.of("https://naver.me/xxxxxxx",
                    "https://maps.app.goo.gl/xxxxxxx",
                    "https://place.map.kakao.com/0000000"),
            "네이버_서치어드바이저_코드", "구글_서치콘솔_코드", "다음_웹마스터도구_PIN");

    public SeoController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("store", store);
        model.addAttribute("siteUrl", SITE_URL);
        model.addAttribute("jsonLd", buildJsonLd());
        return "index";
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *
                Allow: /
                Disallow: /admin/
                Sitemap: %s/sitemap.xml
                #DaumWebMasterTool:%s
                """.formatted(SITE_URL, store.daumPin());
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        String urls = List.of("/", "/menu", "/location").stream()
                .map(p -> "<url><loc>" + SITE_URL + p + "</loc></url>")
                .collect(Collectors.joining());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" + urls + "</urlset>";
    }

    private String buildJsonLd() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("@context", "https://schema.org");
        data.put("@type", "Restaurant");
        data.put("name", store.name());
        data.put("description", store.description());
        data.put("url", SITE_URL);
        data.put("image", store.image());
        data.put("telephone", store.phone());
        data.put("priceRange", store.priceRange());
        data.put("servesCuisine", store.cuisine());
        data.put("address", Map.of(
                "@type", "PostalAddress",
                "streetAddress", store.street(),
                "addressLocality", store.locality(),
                "postalCode", store.postalCode(),
                "addressCountry", "KR"));
        data.put("geo", Map.of("@type", "GeoCoordinates",
                "latitude", store.lat(), "longitude", store.lng()));
        data.put("openingHoursSpecification", store.hours().stream()
                .map(h -> Map.of("@type", "OpeningHoursSpecification",
                        "dayOfWeek", h.days(), "opens", h.opens(), "closes", h.closes()))
                .toList());
        data.put("sameAs", store.sameAs());
        // </script> 조기 종료 방지
        return objectMapper.writeValueAsString(data).replace("</", "<\\/");
    }
}