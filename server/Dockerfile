FROM python:3.11-slim

WORKDIR /srv
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SHOP_DB_PATH=/srv/var/shop.db

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY data ./data
RUN mkdir -p /srv/var

EXPOSE 8000
# 리버스 프록시(Caddy 등) 뒤에서 X-Forwarded-* 를 믿어야 이미지 URL 이 https 로 나간다
CMD ["uvicorn", "--factory", "app.main:app_from_env", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
