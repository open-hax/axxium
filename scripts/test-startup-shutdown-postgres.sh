#!/usr/bin/env bash
# Isolated integration regression; use prebuilt immutable local image IDs only.
# Creates task-owned nonroot containers and a synthetic DB. Containers/network
# are stopped and retained for inspection. No host or existing service is used.
set -euo pipefail
if [[ $# != 2 || ! "$1" =~ ^sha256:[0-9a-f]{64}$ || ! "$2" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo 'Usage: test-startup-shutdown-postgres.sh AXXIUM_IMAGE_ID POSTGRES_IMAGE_ID' >&2
  exit 2
fi
image=$1
postgres=$2
suffix="$(date -u +%Y%m%d%H%M%S)-$$"
network="axxium-startup-tx-$suffix"
db="axxium-startup-tx-db-$suffix"
normal="axxium-startup-tx-normal-$suffix"
app="axxium-startup-tx-app-$suffix"
printf 'Owned fixtures: %s %s %s; network: %s\n' "$normal" "$app" "$db" "$network"
cleanup() {
  for owned in "$normal" "$app" "$db"; do
    if docker inspect --format '{{.State.Running}}' "$owned" 2>/dev/null | grep -qx true; then
      docker stop --time 2 "$owned" >/dev/null || true
    fi
  done
}
trap cleanup EXIT
docker network create --internal --label openhax.task=axxium-startup-tx "$network" >/dev/null
docker run -d --name "$db" --label openhax.task=axxium-startup-tx --network "$network" --network-alias db \
  --user 999:999 --read-only --tmpfs /var/lib/postgresql/data:rw,uid=999,gid=999 \
  --tmpfs /var/run/postgresql:rw,uid=999,gid=999 --tmpfs /tmp:rw \
  --env POSTGRES_USER=fixture --env POSTGRES_DB=fixture --env POSTGRES_PASSWORD=fixture-only "$postgres" >/dev/null
for _ in $(seq 1 45); do
  if docker exec --user 999:999 "$db" pg_isready -U fixture -d fixture >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec --user 999:999 "$db" pg_isready -U fixture -d fixture >/dev/null
run_app() {
  docker run -d --name "$1" --label openhax.task=axxium-startup-tx --network "$network" --user 1000:1000 \
    --stop-timeout 50 --read-only --tmpfs /tmp:rw --env DB_HOST=db --env DB_USER=fixture \
    --env DB_NAME=fixture --env DB_PASSWORD=fixture-only "$image" >/dev/null
}
run_app "$normal"
for _ in $(seq 1 45); do
  if docker exec --user 1000:1000 "$normal" node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.status===200?0:1)).catch(()=>process.exit(1))" >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec --user 1000:1000 "$normal" node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.status===200?0:1)).catch(()=>process.exit(1))" >/dev/null
docker kill --signal TERM "$normal" >/dev/null
[[ $(timeout 8s docker wait "$normal") = 0 ]]
# nextval is deliberately nontransactional: it detects whether the UPDATE
# trigger started even if the surrounding transaction later rolls back.
docker exec -i --user 999:999 "$db" psql -v ON_ERROR_STOP=1 -U fixture -d fixture >/dev/null <<'SQL'
ALTER TABLE actors ALTER COLUMN created_at DROP NOT NULL;
INSERT INTO entities (id,kind) VALUES ('fixture-entity','person');
INSERT INTO actors (id,entity_id,created_at) VALUES ('fixture-actor','fixture-entity',NULL);
CREATE SEQUENCE fixture_update_count;
CREATE FUNCTION fixture_slow_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM nextval('fixture_update_count');
  PERFORM pg_sleep(19);
  RETURN NEW;
END $$;
CREATE TRIGGER fixture_slow_update BEFORE UPDATE ON actors FOR EACH ROW EXECUTE FUNCTION fixture_slow_update();
SQL
docker exec -d --user 999:999 --env PGAPPNAME=axxium-startup-test-lock "$db" psql -U fixture -d fixture \
  -c 'BEGIN; LOCK TABLE actors IN ACCESS SHARE MODE; SELECT pg_sleep(120); ROLLBACK;' >/dev/null
for _ in $(seq 1 20); do
  held=$(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT count(*) FROM pg_locks WHERE relation='actors'::regclass AND mode='AccessShareLock' AND granted")
  [[ "$held" != 0 ]] && break
  sleep 1
done
[[ "$held" != 0 ]]
run_app "$app"
for _ in $(seq 1 35); do
  blocked=$(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT count(*) FROM pg_stat_activity WHERE usename='fixture' AND wait_event_type='Lock' AND query LIKE 'LOCK TABLE actors IN ACCESS EXCLUSIVE MODE%'")
  [[ "$blocked" = 1 ]] && break
  sleep 1
done
[[ "$blocked" = 1 ]]
docker kill --signal TERM "$app" >/dev/null
docker kill --signal INT "$app" >/dev/null
# Release the blocker before the statement timeout; the interrupted migration
# must roll back immediately instead of starting the delayed UPDATE.
sleep 12
docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='axxium-startup-test-lock'" >/dev/null
if ! status=$(timeout 8s docker wait "$app"); then
  triggered=$(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc 'SELECT is_called FROM fixture_update_count')
  printf 'FAIL app remained running after lock release; delayed UPDATE trigger started=%s\n' "$triggered" >&2
  exit 1
fi
[[ "$status" = 0 ]]
app_logs=$(docker logs "$app" 2>&1)
if grep -q 'Axxium listening on' <<< "$app_logs"; then
  printf 'FAIL interrupted startup reached listening\n' >&2
  exit 1
fi
[[ $(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc 'SELECT is_called FROM fixture_update_count') = f ]]
[[ $(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT created_at IS NULL FROM actors WHERE id='fixture-actor'") = t ]]
[[ $(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='actors' AND column_name='created_at'") = YES ]]
[[ $(docker exec --user 999:999 "$db" psql -U fixture -d fixture -Atc "SELECT count(*) FROM pg_stat_activity WHERE usename='fixture' AND backend_type='client backend' AND pid<>pg_backend_pid()") = 0 ]]
printf 'PASS interrupted locked migration rolled back without starting UPDATE; no listener or remaining DB backend\n'
printf 'Fixture containers retained: %s %s %s; network: %s\n' "$normal" "$app" "$db" "$network"
