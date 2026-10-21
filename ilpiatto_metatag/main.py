import json
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, Response
from fastapi.templating import Jinja2Templates

app = FastAPI()
templates = Jinja2Templates(directory="templates")

SITE_URL = "https://www.ilpiatto.co.kr"   # 실제 도메인으로 교체

STORE = {
    "name": "일피아토",
    "description": "산본동 이탈리안 레스토랑 일피아토 - 직접 만든 파스타와 함박 스테이크를 편안한 분위기에서 즐겨보세요. 메뉴, 영업시간, 위치 안내",
    "keywords": "일피아토, 산본동 맛집, 파스타, 이탈리안 레스토랑",
    "phone": "+82-031-396-8111",
    "street": "수리산로 191-1",
    "locality": "경기도 군포시 산본동",
    "postal_code": "00000",
    "lat": 37.3512,
    "lng": 126.9248,
    "image": f"{SITE_URL}/static/og-image.jpg",   # 1200x630 권장
    "price_range": "1.0~2.5",
    "cuisine": "Italian",
    "hours": [
        {"days": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"], "opens": "11:00", "closes": "21:30"},
    ],
    # 플랫폼 등록 후 받은 URL
    "same_as": [
        "https://naver.me/GNJL6BZZ",
        "https://maps.app.goo.gl/iUJyKUGpHXXGEyom8",
        "https://place.map.kakao.com/736552898",
    ],
    # 각 검색엔진에서 발급받은 값
    "naver_verification": "e00bf01a667b75cc3e3614f2cbe554e7d6e6154e",
    "google_verification": "2uKErFqXSB-rIPo_AxqyXW-i01oMhKjmkOMy0sPmLsM",
    "daum_pin": "ghtjd1200",
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
