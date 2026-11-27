#!/usr/bin/env bash
# ============================================================
# build-containers.sh
# Đặt cạnh 3 thư mục: app_mobile, be-web-admin, fe_web_admin
#
# Cách dùng:
#   chmod +x build-containers.sh
#   ./build-containers.sh init        # tạo .env mới với secrets ngẫu nhiên (chỉ khi chưa có file)
#   ./build-containers.sh up          # kiểm tra secrets, build và chạy cả 5 container (mặc định)
#   ./build-containers.sh build       # kiểm tra secrets, chỉ build image, không chạy
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

read_env_value() {
  local key="$1" envfile="$2" value
  value="$(awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$envfile")"
  value="${value%$'\r'}"
  printf '%s' "$value"
}

validate_base64_secret() {
  local key="$1" envfile="$2" value tmp bytes
  value="$(read_env_value "$key" "$envfile")"
  if [[ -z "$value" || "$value" == *change-me* ]]; then
    red "$key thiếu hoặc vẫn là giá trị mẫu. Tạo khóa Base64 chuẩn bằng: openssl rand -base64 32"
    if [[ "$key" == TOTP_MASTER_KEY_V2 ]]; then
      red "Chỉ tạo TOTP_MASTER_KEY_V2 mới khi chắc chắn DB không có credential TOTP; nếu có, phải khôi phục khóa gốc."
    fi
    return 1
  fi

  tmp="$(mktemp)" || return 1
  if ! printf '%s' "$value" | base64 --decode >"$tmp" 2>/dev/null; then
    rm -f "$tmp"
    red "$key không phải Base64 chuẩn. Không dùng Base64 URL-safe (ký tự '-' hoặc '_')."
    if [[ "$key" == TOTP_MASTER_KEY_V2 ]]; then
      red "Nếu DB đã có credential TOTP, khôi phục khóa gốc thay vì sinh khóa mới."
    fi
    return 1
  fi
  bytes="$(wc -c <"$tmp")"
  rm -f "$tmp"
  if (( bytes < 32 )); then
    red "$key giải mã được nhưng chỉ có $bytes bytes; cần ít nhất 32 bytes ngẫu nhiên."
    return 1
  fi
}

validate_env_secrets() {
  local envfile="$1" key value
  local valid=1
  for key in MARIADB_PASSWORD REDIS_PASSWORD; do
    value="$(read_env_value "$key" "$envfile")"
    if [[ -z "$value" || "$value" == *change-me* || ${#value} -lt 24 ]]; then
      red "$key còn thiếu/yếu hoặc vẫn là giá trị mẫu; cần mật khẩu ngẫu nhiên dài ít nhất 24 ký tự."
      valid=0
    fi
  done
  validate_base64_secret TOTP_MASTER_KEY_V2 "$envfile" || valid=0
  validate_base64_secret JWT_SECRET_BASE64 "$envfile" || valid=0
  (( valid == 1 ))
}

cmd_init() {
  local envfile="$ROOT/be-web-admin/.env" tmp
  if [[ -e "$envfile" ]]; then
    red "Không ghi đè $envfile: file/volume hiện có có thể chứa thông tin đăng nhập và khóa giải mã dữ liệu."
    red "Với cài đặt đã tồn tại, hãy khôi phục TOTP_MASTER_KEY_V2 gốc; chỉ tạo mới khi chắc chắn chưa có dữ liệu TOTP."
    return 1
  fi
  if docker volume ls -q --filter label=com.docker.compose.project=totp-backend | grep -q .; then
    red "Đã tồn tại Docker volume của totp-backend; không tự sinh khóa/mật khẩu mới vì có thể làm mất quyền truy cập dữ liệu."
    return 1
  fi
  command -v openssl >/dev/null 2>&1 || { red "Cần cài OpenSSL để sinh secrets ngẫu nhiên."; return 1; }

  local db_password redis_password totp_key jwt_key
  db_password="$(openssl rand -hex 24)"
  redis_password="$(openssl rand -hex 24)"
  totp_key="$(openssl rand -base64 32 | tr -d '\n')"
  jwt_key="$(openssl rand -base64 32 | tr -d '\n')"
  umask 077
  tmp="$(mktemp "$ROOT/be-web-admin/.env.tmp.XXXXXX")" || return 1
  cat >"$tmp" <<EOF
# Tự sinh bởi ./build-containers.sh init. Không commit file này.
IP_ADDRESS=127.0.0.1
SPRING_BOOT_PORT=8081
MARIADB_PORT=3307
MARIADB_DBS=ntt_university
MARIADB_USERNAME=root
MARIADB_PASSWORD=$db_password
REDIS_PORT=6380
REDIS_USERNAME=default
REDIS_PASSWORD=$redis_password
REDIS_DBS=1
TOTP_MASTER_KEY_V2=$totp_key
JWT_SECRET_BASE64=$jwt_key
CORS_ALLOWED_ORIGINS=http://localhost:5374,http://localhost:5375,http://localhost:5173
AUTH_COOKIE_SECURE=false
JPA_DDL_AUTO=update
EOF
  chmod 600 "$tmp"
  mv "$tmp" "$envfile"
  green "Đã tạo $envfile với secrets ngẫu nhiên (quyền truy cập 600). Hãy sao lưu khóa TOTP an toàn."
}

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
  validate_env_secrets "$envfile" || exit 1
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
    init)    cmd_init ;;
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
