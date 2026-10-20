import json
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, Response
from fastapi.templating import Jinja2Templates

app = FastAPI()
templates = Jinja2Templates(directory="templates")

SITE_URL = "https://www.example.com"   # 실제 도메인으로 교체

STORE = {
    "name": "일피아또",
    "description": "OO동 이탈리안 레스토랑 일피아또 - 생면 파스타와 화덕 피자",
    "keywords": "일피아또, OO동 맛집, 파스타, 이탈리안 레스토랑",
    "phone": "+82-2-000-0000",
    "street": "OO로 00",
    "locality": "서울특별시 OO구",
    "postal_code": "00000",
    "lat": 37.5665,
    "lng": 126.9780,
    "image": f"{SITE_URL}/static/og-image.jpg",   # 1200x630 권장
    "price_range": "₩₩",
    "cuisine": "Italian",
    "hours": [
        {"days": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"], "opens": "11:30", "closes": "22:00"},
        {"days": ["Saturday", "Sunday"], "opens": "12:00", "closes": "21:00"},
    ],
    # 플랫폼 등록 후 받은 URL
    "same_as": [
        "https://naver.me/xxxxxxx",
        "https://maps.app.goo.gl/xxxxxxx",
        "https://place.map.kakao.com/0000000",
    ],
    # 각 검색엔진에서 발급받은 값
    "naver_verification": "네이버_서치어드바이저_코드",
    "google_verification": "구글_서치콘솔_코드",
    "daum_pin": "다음_웹마스터도구_PIN",
}


def build_jsonld(store: dict) -> str:
    data = {
        "@context": "https://schema.org",
        "@type": "Restaurant",
        "name": store["name"],
        "description": store["description"],
        "url": SITE_URL,
        "image": store["image"],
        "telephone": store["phone"],
        "priceRange": store["price_range"],
        "servesCuisine": store["cuisine"],
        "address": {
            "@type": "PostalAddress",
            "streetAddress": store["street"],
            "addressLocality": store["locality"],
            "postalCode": store["postal_code"],
            "addressCountry": "KR",
        },
        "geo": {"@type": "GeoCoordinates", "latitude": store["lat"], "longitude": store["lng"]},
        "openingHoursSpecification": [
            {"@type": "OpeningHoursSpecification", "dayOfWeek": h["days"],
             "opens": h["opens"], "closes": h["closes"]}
            for h in store["hours"]
        ],
        "sameAs": store["same_as"],
    }
    # </script> 조기 종료 방지
    return json.dumps(data, ensure_ascii=False).replace("</", "<\\/")


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    return templates.TemplateResponse(
        request, "index.html",
        {"store": STORE, "site_url": SITE_URL, "jsonld": build_jsonld(STORE)},
    )


@app.get("/robots.txt", response_class=PlainTextResponse)
def robots():
    return (
        "User-agent: *\n"
        "Allow: /\n"
        "Disallow: /admin/\n"
        f"Sitemap: {SITE_URL}/sitemap.xml\n"
        f"#DaumWebMasterTool:{STORE['daum_pin']}\n"
    )


@app.get("/sitemap.xml")
def sitemap():
    pages = ["/", "/menu", "/location"]
    urls = "".join(f"<url><loc>{SITE_URL}{p}</loc></url>" for p in pages)
    xml = ('<?xml version="1.0" encoding="UTF-8"?>'
           f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>')
    return Response(content=xml, media_type="application/xml")