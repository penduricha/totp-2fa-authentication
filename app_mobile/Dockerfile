# syntax=docker/dockerfile:1
#
# Một Dockerfile, hai đầu ra:
#   target "web" : Flutter web + Nginx (mặc định, nằm cuối file)
#   target "apk" : file app-debug.apk (x86_64) để cài lên emulator của Android Studio
#
ARG FLUTTER_VERSION=3.47.2
ARG NGINX_VERSION=1.26
ARG ANDROID_CMDLINE_TOOLS=11076708

# ---------- Nền chung: Flutter SDK ----------
FROM ubuntu:24.04 AS flutter-sdk

ARG FLUTTER_VERSION
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       git curl unzip xz-utils zip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch "${FLUTTER_VERSION}" https://github.com/flutter/flutter.git /opt/flutter
ENV PATH="/opt/flutter/bin:/opt/flutter/bin/cache/dart-sdk/bin:${PATH}"

RUN flutter config --no-analytics && flutter --version

# Script ghi file .env cho BẢN WEB từ biến môi trường.
# Chạy lúc build (để flutter_dotenv có file .env làm asset) và chạy lại mỗi lần container khởi động,
# nên đổi PUBLIC_HOST không cần build lại image. Mặc định 10.0.2.2 = chạy trên Chrome của emulator.
COPY --chmod=755 <<"EOF" /usr/local/bin/gen-env.sh
#!/bin/sh
set -eu
TARGET="${1:-/usr/share/nginx/html/assets/.env}"
mkdir -p "$(dirname "$TARGET")"
cat > "$TARGET" <<ENV
HOST_ANDROID_EMULATOR=10.0.2.2
HOST_DEFAULT=${PUBLIC_HOST:-10.0.2.2}
SPRING_HOST=${PUBLIC_HOST:-10.0.2.2}
NODEJS_HOST=${PUBLIC_HOST:-10.0.2.2}
SPRING_PROTOCOL=http
SPRING_PORT=${WEB_PORT:-5375}
SPRING_API_PREFIX=${SPRING_API_PREFIX:-/api/mobile}
NODEJS_PROTOCOL=http
NODEJS_PORT=${NODEJS_PORT:-3001}
NODEJS_API_PREFIX=${NODEJS_API_PREFIX:-/api/mobile}
CONNECT_TIMEOUT_SECONDS=${CONNECT_TIMEOUT_SECONDS:-10}
SEND_TIMEOUT_SECONDS=${SEND_TIMEOUT_SECONDS:-15}
RECEIVE_TIMEOUT_SECONDS=${RECEIVE_TIMEOUT_SECONDS:-15}
ENV
echo "gen-env: da ghi $TARGET (host=${PUBLIC_HOST:-10.0.2.2})"
EOF

# ---------- Build WEB ----------
FROM flutter-sdk AS build-web

RUN flutter precache --web

WORKDIR /app

# Tách bước tải thư viện để Docker cache: chỉ chạy lại khi pubspec đổi
COPY pubspec.yaml pubspec.lock* ./
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

COPY . .

# Bản web dùng .env sinh ra từ script (không đưa bí mật vào: file này tải được từ trình duyệt)
RUN gen-env.sh /app/.env

RUN [ -d web ] || flutter create . --platforms=web

# --pwa-strategy=none: tắt service worker để trình duyệt không giữ bản .env cũ khi đổi PUBLIC_HOST
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get \
    && flutter build web --release --no-web-resources-cdn --pwa-strategy=none

# ---------- Build APK (debug, x86_64: chạy trên emulator Android Studio) ----------
FROM flutter-sdk AS build-apk

ARG ANDROID_CMDLINE_TOOLS
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/cmdline-tools.zip \
       "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip" \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

# Chấp nhận license; các gói platform / build-tools còn thiếu sẽ do Gradle tự tải ở lần build đầu
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" \
    && flutter config --android-sdk "${ANDROID_HOME}" \
    && flutter precache --android

WORKDIR /app

COPY pubspec.yaml pubspec.lock* ./
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

COPY . .

# Bản mobile dùng NGUYÊN file .env của dự án: emulator tự chọn HOST_ANDROID_EMULATOR (10.0.2.2) + SPRING_PORT (8081).
# Android 9+ chặn HTTP thường: chỉ trong container này, thêm usesCleartextTraffic để gọi http://10.0.2.2.
RUN [ -d android ] || flutter create . --platforms=android
RUN grep -q usesCleartextTraffic android/app/src/main/AndroidManifest.xml \
    || sed -i 's|<application|<application android:usesCleartextTraffic="true"|' android/app/src/main/AndroidManifest.xml

RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/root/.gradle \
    flutter pub get \
    && flutter build apk --debug --target-platform android-x64

# ---------- Đầu ra: chỉ chứa file APK (image nhỏ) ----------
FROM alpine:3.20 AS apk

COPY --from=build-apk /app/build/app/outputs/flutter-apk/app-debug.apk /out/app-debug.apk
CMD ["cp", "-v", "/out/app-debug.apk", "/dist/app-debug.apk"]

# ---------- Đầu ra: Nginx phục vụ web (target mặc định, phải nằm CUỐI file) ----------
FROM nginx:${NGINX_VERSION}-alpine AS web

COPY <<"EOF" /etc/nginx/conf.d/default.conf
server {
    listen 5375;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # DNS nội bộ của Docker: phân giải spring-app lúc chạy, nên Nginx vẫn khởi động khi Spring chưa lên
    resolver 127.0.0.11 valid=10s ipv6=off;

    gzip on;
    gzip_types text/css application/javascript application/json application/wasm image/svg+xml;

    # Cùng origin với trang web => không cần cấu hình CORS
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

    # Không cache: các file khởi động và file cấu hình .env (đổi được mà không build lại)
    location ~ ^/(index\.html|flutter_service_worker\.js|flutter_bootstrap\.js|version\.json|assets/\.env)$ {
        add_header Cache-Control "no-store";
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
        try_files $uri =404;
    }

    location / {
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
        try_files $uri $uri/ /index.html;
    }
}
EOF

COPY --from=build-web /app/build/web /usr/share/nginx/html

# Image nginx tự chạy mọi file *.sh có quyền thực thi trong thư mục này trước khi bật nginx
COPY --from=build-web --chmod=755 /usr/local/bin/gen-env.sh /docker-entrypoint.d/40-gen-env.sh

EXPOSE 5375