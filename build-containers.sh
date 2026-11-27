#!/usr/bin/env bash
# ============================================================
# build-containers.sh
# Đặt cạnh 3 thư mục: app_mobile, be-web-admin, fe_web_admin
#
# Cách dùng:
#   chmod +x build-containers.sh
#   ./build-containers.sh up          # build và chạy cả 5 container (mặc định)
#   ./build-containers.sh build       # chỉ build image, không chạy
#   ./build-containers.sh down        # dừng và xóa container (giữ dữ liệu DB, Redis)
#   ./build-containers.sh clean       # như down, nhưng XÓA luôn volume dữ liệu và mạng
#   ./build-containers.sh restart     # down rồi up
#   ./build-containers.sh ps          # xem trạng thái
#   ./build-containers.sh logs [tên]  # xem log, mặc định spring-app
#
# Truy cập từ máy khác trong LAN:
#   PUBLIC_HOST=192.168.1.10 ./build-containers.sh up
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK="totp-net"

BE_COMPOSE="$ROOT/be-web-admin/docker-compose.yml"
FE_COMPOSE="$ROOT/fe_web_admin/docker-compose.yml"
APP_COMPOSE="$ROOT/app_mobile/docker-compose.yml"

# Địa chỉ mà trình duyệt dùng để mở web (được nhúng vào bản build của Vue và Flutter)
export PUBLIC_HOST="${PUBLIC_HOST:-localhost}"
export DOCKER_BUILDKIT=1

# Cổng publish ra máy chủ
PORTS=(8081 5374 5375 3307 6380)

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
step()   { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

check_docker() {
  command -v docker >/dev/null 2>&1 || { red "Chưa cài Docker. Cài bằng: sudo apt install -y docker.io docker-compose-v2"; exit 1; }
  docker compose version >/dev/null 2>&1 || { red "Chưa có Docker Compose v2. Cài bằng: sudo apt install -y docker-compose-v2"; exit 1; }
  docker info >/dev/null 2>&1 || { red "Không kết nối được Docker. Thử: sudo usermod -aG docker \$USER rồi đăng nhập lại, hoặc chạy bằng sudo."; exit 1; }
}

check_files() {
  local missing=0
  for f in \
    "$BE_COMPOSE" "$ROOT/be-web-admin/Dockerfile" \
    "$FE_COMPOSE" "$ROOT/fe_web_admin/Dockerfile" \
    "$APP_COMPOSE" "$ROOT/app_mobile/Dockerfile"; do
    [[ -f "$f" ]] || { red "Thiếu file: $f"; missing=1; }
  done
  [[ $missing -eq 0 ]] || exit 1

  local envfile="$ROOT/be-web-admin/.env"
  if [[ ! -f "$envfile" ]]; then
    red "Thiếu $envfile (chứa MARIADB_*, REDIS_*, TOTP_MASTER_KEY_V2, JWT_SECRET_BASE64...)."
    exit 1
  fi
  for key in MARIADB_PASSWORD MARIADB_DBS REDIS_PASSWORD TOTP_MASTER_KEY_V2 JWT_SECRET_BASE64; do
    grep -Eq "^${key}=.+" "$envfile" || { red "File .env của be-web-admin thiếu hoặc để trống: $key"; exit 1; }
  done
  [[ -x "$ROOT/be-web-admin/gradlew" || -f "$ROOT/be-web-admin/gradlew" ]] || \
    yellow "Cảnh báo: be-web-admin chưa có gradlew, bước build Spring Boot sẽ lỗi. Chạy 'gradle wrapper' trong thư mục đó."
}

check_ports() {
  command -v ss >/dev/null 2>&1 || return 0
  for p in "${PORTS[@]}"; do
    if ss -ltn "( sport = :$p )" 2>/dev/null | grep -q LISTEN; then
      # Cổng đang do chính container của dự án giữ thì bỏ qua
      if ! docker ps --format '{{.Ports}}' | grep -q ":$p->"; then
        yellow "Cảnh báo: cổng $p đang được tiến trình khác trên máy dùng (ví dụ MariaDB/Redis/Spring chạy cục bộ). Hãy tắt trước khi chạy."
      fi
    fi
  done
}

ensure_network() {
  if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
    step "Tạo mạng Docker dùng chung: $NETWORK"
    docker network create "$NETWORK" >/dev/null
  fi
}

cmd_build() {
  step "Build be-web-admin (Spring Boot)"
  docker compose -f "$BE_COMPOSE" build
  step "Build fe_web_admin (Vue + Nginx)"
  docker compose -f "$FE_COMPOSE" build
  step "Build app_mobile (Flutter web + Nginx), lần đầu có thể mất vài phút"
  docker compose -f "$APP_COMPOSE" build
}

cmd_up() {
  step "Chạy backend: MariaDB, Redis, Spring Boot"
  docker compose -f "$BE_COMPOSE" up -d --build
  step "Chạy Vue + Nginx"
  docker compose -f "$FE_COMPOSE" up -d --build
  step "Chạy Flutter web + Nginx"
  docker compose -f "$APP_COMPOSE" up -d --build

  echo
  green "Đã khởi động xong. Truy cập:"
  echo "  Vue web admin : http://${PUBLIC_HOST}:5374"
  echo "  Flutter web   : http://${PUBLIC_HOST}:5375"
  echo "  Spring Boot   : http://${PUBLIC_HOST}:8081"
  echo "  MariaDB       : ${PUBLIC_HOST}:3307  (database lấy từ MARIADB_DBS)"
  echo "  Redis         : ${PUBLIC_HOST}:6380  (db số 1)"
  echo
  echo "Xem log Spring Boot: ./build-containers.sh logs spring-app"
}

cmd_down() {
  step "Dừng container"
  docker compose -f "$APP_COMPOSE" down --remove-orphans || true
  docker compose -f "$FE_COMPOSE"  down --remove-orphans || true
  docker compose -f "$BE_COMPOSE"  down --remove-orphans || true
}

cmd_clean() {
  yellow "Lệnh này xóa toàn bộ dữ liệu MariaDB và Redis trong Docker (volume)."
  read -r -p "Gõ 'yes' để tiếp tục: " ans
  [[ "$ans" == "yes" ]] || { echo "Đã hủy."; exit 0; }
  step "Xóa container và volume"
  docker compose -f "$APP_COMPOSE" down -v --remove-orphans || true
  docker compose -f "$FE_COMPOSE"  down -v --remove-orphans || true
  docker compose -f "$BE_COMPOSE"  down -v --remove-orphans || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  green "Đã dọn xong."
}

cmd_ps() {
  docker ps -a --filter "network=$NETWORK" \
    --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
}

cmd_logs() {
  docker logs -f --tail 200 "${1:-spring-app}"
}

main() {
  local action="${1:-up}"
  shift || true

  check_docker
  case "$action" in
    up)      check_files; check_ports; ensure_network; cmd_up ;;
    build)   check_files; ensure_network; cmd_build ;;
    down)    cmd_down ;;
    clean)   cmd_clean ;;
    restart) cmd_down; check_files; ensure_network; cmd_up ;;
    ps|status) cmd_ps ;;
    logs)    cmd_logs "${1:-spring-app}" ;;
    *)       sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 1 ;;
  esac
}

main "$@"
