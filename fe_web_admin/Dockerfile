# syntax=docker/dockerfile:1
# ============================================================
# fe_web_admin: Vue 3 + Vite, build bằng Node 20 LTS, phục vụ bằng Nginx
# Nginx lắng nghe cổng 5374 và chuyển tiếp /api/ sang Spring Boot (cùng origin, không cần CORS)
# ============================================================

ARG NODE_VERSION=20
ARG NGINX_VERSION=1.26

# ---------- Giai đoạn 1: build ----------
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY . .

# VITE_* được nhúng lúc build. API dùng URL tương đối cùng origin; Nginx proxy /api sang Spring,
# nên truy cập được qua HTTP local hoặc HTTPS reverse proxy mà không rò rỉ localhost/mixed content.
ARG VITE_SPRING_PROTOCOL=http
ARG VITE_SPRING_HOST=localhost
ARG VITE_SPRING_PORT=5374
ARG VITE_SPRING_API_PREFIX=/api/web
ARG VITE_SPRING_BASE_URL=/api/web
ARG VITE_NODEJS_PROTOCOL=http
ARG VITE_NODEJS_HOST=localhost
ARG VITE_NODEJS_PORT=3001
ARG VITE_NODEJS_API_PREFIX=/api/web
ARG VITE_REQUEST_TIMEOUT_MS=15000

ENV VITE_SPRING_PROTOCOL=${VITE_SPRING_PROTOCOL} \
    VITE_SPRING_HOST=${VITE_SPRING_HOST} \
    VITE_SPRING_PORT=${VITE_SPRING_PORT} \
    VITE_SPRING_API_PREFIX=${VITE_SPRING_API_PREFIX} \
    VITE_SPRING_BASE_URL=${VITE_SPRING_BASE_URL} \
    VITE_NODEJS_PROTOCOL=${VITE_NODEJS_PROTOCOL} \
    VITE_NODEJS_HOST=${VITE_NODEJS_HOST} \
    VITE_NODEJS_PORT=${VITE_NODEJS_PORT} \
    VITE_NODEJS_API_PREFIX=${VITE_NODEJS_API_PREFIX} \
    VITE_REQUEST_TIMEOUT_MS=${VITE_REQUEST_TIMEOUT_MS}

RUN npm run build

# ---------- Giai đoạn 2: Nginx ----------
FROM nginx:${NGINX_VERSION}-alpine

# Nhãn "EOF" đặt trong dấu nháy kép để Docker không thay thế $uri, $host... của Nginx
COPY <<"EOF" /etc/nginx/conf.d/default.conf
server {
    listen 5374;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # DNS nội bộ của Docker: tra tên spring-app mỗi lần gọi, nên Nginx vẫn khởi động
    # được khi container Spring chưa chạy
    resolver 127.0.0.11 valid=10s ipv6=off;

    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;

    # API chuyển sang Spring Boot, giữ nguyên đường dẫn /api/...
    location /api/ {
        set $spring http://spring-app:8081;
        proxy_pass $spring;
        proxy_set_header Host              $http_host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout    30s;
    }

    # File build có mã băm trong tên nên cache dài hạn
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'" always;
        add_header Referrer-Policy no-referrer always;
        add_header X-Content-Type-Options nosniff always;
        try_files $uri =404;
    }

    # index.html không cache để luôn nhận bản build mới
    location = /index.html {
        add_header Cache-Control "no-store";
        add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'" always;
        add_header Referrer-Policy no-referrer always;
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
    }

    # Vue Router chế độ history: đường dẫn không phải file thì trả index.html
    location / {
        add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'" always;
        add_header Referrer-Policy no-referrer always;
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
        try_files $uri $uri/ /index.html;
    }
}
EOF

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 5374
