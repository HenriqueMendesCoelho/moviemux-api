#!/usr/bin/env bash
set -euo pipefail

# Only synthetic fixtures. The runtime network has no external connectivity.
image="${1:?Usage: smoke-container.sh local-image-tag}"
prefix="moviemux-smoke-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-1}"
network="${prefix}-network"
database="${prefix}-postgres"
api="${prefix}-api"
port="${SMOKE_PORT:-18080}"
cleanup() {
  status=$?
  trap - EXIT
  if [ "$status" -ne 0 ]; then
    docker logs "$api" 2>&1 || true
    docker logs "$database" 2>&1 || true
  fi
  docker rm -f "$api" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

docker network create --internal "$network" >/dev/null
test "$(docker network inspect -f '{{.Internal}}' "$network")" = true
docker run -d --name "$database" --network "$network" --network-alias db \
  -e POSTGRES_DB=moviemux_smoke -e POSTGRES_USER=moviemux_smoke \
  -e POSTGRES_PASSWORD=synthetic-smoke-password postgres:18.1 >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$database" pg_isready -U moviemux_smoke -d moviemux_smoke >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$database" pg_isready -U moviemux_smoke -d moviemux_smoke

docker run -d --name "$api" --network "$network" -p "127.0.0.1:${port}:8080" \
  -e DB_URL=jdbc:postgresql://db:5432/moviemux_smoke \
  -e DB_USER=moviemux_smoke -e DB_PASS=synthetic-smoke-password -e DB_SCHEMA=public \
  -e JWT_SECRET=synthetic-container-smoke-secret-at-least-32-bytes \
  -e CINE_SEND_MAIL=false -e MOVIEMUX_MAIL_URL=http://127.0.0.1:1 \
  -e KIT_URL=http://127.0.0.1:1 -e KIT_KEY=synthetic \
  -e DISCORD_WEBHOOK_URL=http://127.0.0.1:1 -e DISCORD_WEBHOOK_EXECUTE=false \
  -e DISCORD_WEBHOOK_UPDATE=false -e DISCORD_WEBHOOK_ROLE_ID=synthetic \
  -e SOCKET_IO_API_URL=http://127.0.0.1:1 -e SOCKET_IO_API_KEY=synthetic \
  "$image" --scheduling.enabled=false >/dev/null

python3 - "$port" "$api" <<'PY'
import json, subprocess, sys, time, urllib.error, urllib.request
base = f"http://127.0.0.1:{sys.argv[1]}/api/monolith"
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(method, path, data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(),
                                 headers=headers, method=method)
    with http.open(req, timeout=5) as response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None

def wait_ready(token=None):
    for _ in range(90):
        try:
            status, body = request("GET", "/actuator/health", token=token)
            if token and status == 200 and body["status"] == "UP":
                return
        except urllib.error.HTTPError as error:
            if not token and error.code in (401, 403):
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise AssertionError("Container did not become ready within 90 seconds")

wait_ready()
status, _ = request("POST", "/public/user", {
    "name": "Synthetic Container Admin", "email": "container-smoke@example.invalid",
    "password": "SyntheticPassword123!", "notify": False, "invite_code": ""})
assert status == 200, "Synthetic signup failed"
status, login = request("POST", "/login", {
    "email": "container-smoke@example.invalid", "password": "SyntheticPassword123!"})
assert status == 200 and login.get("access_token"), "Synthetic login failed"
token = login["access_token"]
wait_ready(token)
subprocess.run(["docker", "restart", sys.argv[2]], check=True, stdout=subprocess.DEVNULL)
wait_ready(token)
print("PASS: container signup, login, authenticated database health and restart")
PY
history="$(docker exec "$database" psql -U moviemux_smoke -d moviemux_smoke -Atc \
  "select count(*) from public.flyway_schema_history where version='1' and type='SQL' and success")"
test "$history" = 1
tables="$(docker exec "$database" psql -U moviemux_smoke -d moviemux_smoke -Atc \
  "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'")"
test "$tables" = 14
printf '%s\n' 'PASS: V1 applied exactly once; 13 application tables plus Flyway history'
