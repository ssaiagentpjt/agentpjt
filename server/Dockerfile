FROM python:3.11-slim

# GHCR 패키지를 저장소에 연결한다(패키지 페이지에 저장소가 표시되고 권한이 이어진다)
LABEL org.opencontainers.image.source=https://github.com/ssaiagentpjt/agentpjt
LABEL org.opencontainers.image.description="손주야 목업 상품 API"

WORKDIR /srv
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SHOP_DB_PATH=/srv/var/shop.db

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY data ./data
RUN mkdir -p /srv/var

EXPOSE 8000
# 이미지에 curl 이 없어 파이썬 표준 라이브러리로 확인한다. 시작할 때 상품 3천 개 적재에 1초 안팎 걸려 start-period 를 둔다
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"]
# 리버스 프록시(cloudflared 등) 뒤에서 X-Forwarded-* 를 믿어야 이미지 URL 이 https 로 나간다
CMD ["uvicorn", "--factory", "app.main:app_from_env", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
