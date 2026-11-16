# syntax=docker/dockerfile:1
# ============================================================
# be-web-admin: Spring Boot 3.x + Gradle, Java 21
# Build 2 giai đoạn: JDK để biên dịch, JRE để chạy (image nhỏ, không chứa mã nguồn)
# Image name (đặt trong docker-compose.yml): totp-be
# ============================================================

ARG JAVA_VERSION=21

# ---------- Giai đoạn 1: build ----------
FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS build
WORKDIR /workspace

# Chỉ copy những gì cần để build. File .env, .git, build/... đã bị loại bởi .dockerignore
COPY . .

# - sed: xóa ký tự CRLF nếu gradlew được commit từ Windows (tránh lỗi "bad interpreter").
# - Cache Gradle giữa các lần build để không phải tải lại thư viện.
# - Cần có gradlew trong project. Nếu thiếu, chạy `gradle wrapper` trên máy rồi build lại.
RUN --mount=type=cache,target=/root/.gradle \
    sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon bootJar -x test \
    && mkdir /out \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -n 1)" /out/app.jar

# ---------- Giai đoạn 2: chạy ----------
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy
WORKDIR /app

# Chạy bằng user thường, không dùng root
RUN groupadd --system app && useradd --system --gid app --uid 1001 app

COPY --from=build --chown=app:app /out/app.jar /app/app.jar

# App dùng dotenv-java nên BẮT BUỘC có file /app/.env (working directory = /app).
# Lưu ý: khóa/mật khẩu nằm trong image, không đẩy image này lên registry công khai.
COPY --chown=app:app .env /app/.env

USER app

EXPOSE 8081

# Exec form để java nhận tín hiệu SIGTERM (docker stop tắt êm).
# JVM option đặt qua biến JAVA_TOOL_OPTIONS trong docker-compose.yml.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]