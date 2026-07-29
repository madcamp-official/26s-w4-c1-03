#!/usr/bin/env bash
set -Eeuo pipefail

# CAMP-2 demo launcher. It only touches GAMDO systemd units and creates a
# local SSH tunnel; it does not stop/remove any unrelated service or data.
GPU_HOST="${GPU_HOST:-172.10.5.176}"
GPU_USER="${GPU_USER:-root}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kcloud_gpu_vm_key.pem}"
LOCAL_PORT="${LOCAL_PORT:-18000}"
REMOTE_PORT="${REMOTE_PORT:-8000}"
BASE_URL="http://127.0.0.1:${LOCAL_PORT}"

if ! command -v ssh >/dev/null 2>&1; then
  echo "ssh is required" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi
if [[ ! -r "$SSH_KEY" ]]; then
  echo "SSH key is not readable: $SSH_KEY" >&2
  exit 1
fi

SSH=(ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" "${GPU_USER}@${GPU_HOST}")

echo "Starting GAMDO services on ${GPU_HOST}..."
"${SSH[@]}" "systemctl start gamdo-comfyui.service gamdo-server.service gamdo-worker.service"

TUNNEL_PID=""
cleanup() {
  if [[ -n "$TUNNEL_PID" ]] && kill -0 "$TUNNEL_PID" 2>/dev/null; then
    kill "$TUNNEL_PID" 2>/dev/null || true
    wait "$TUNNEL_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "Opening ${BASE_URL} -> ${GPU_HOST}:127.0.0.1:${REMOTE_PORT}"
ssh -N -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" \
  -L "${LOCAL_PORT}:127.0.0.1:${REMOTE_PORT}" "${GPU_USER}@${GPU_HOST}" &
TUNNEL_PID=$!

for attempt in $(seq 1 20); do
  if curl --fail --silent --show-error "${BASE_URL}/health" >/dev/null; then
    echo "GAMDO API is ready at ${BASE_URL}"
    echo "Press Ctrl-C to close the tunnel."
    wait "$TUNNEL_PID"
    exit 0
  fi
  sleep 1
done

echo "GAMDO API did not become ready within 20 seconds" >&2
"${SSH[@]}" "systemctl --no-pager --full status gamdo-comfyui.service gamdo-server.service gamdo-worker.service" || true
exit 1
