#!/bin/bash
# GCE startup script for the LoraViewer track server. Runs on every boot and is idempotent:
# installs Caddy (HTTPS via Let's Encrypt on <external-ip>.sslip.io), pulls the server code and
# API key from instance metadata, and (re)starts the service. To deploy new server code, update
# the "trackserver-py" metadata and reboot the VM (or re-run this script).
set -euo pipefail

MD=http://metadata.google.internal/computeMetadata/v1
md() { curl -fsS -H "Metadata-Flavor: Google" "$MD/$1"; }

if ! command -v caddy >/dev/null || ! command -v python3 >/dev/null; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y caddy python3
fi

install -d -m 755 /opt/loraviewer-track
md instance/attributes/trackserver-py > /opt/loraviewer-track/trackserver.py

id -u loraviewer >/dev/null 2>&1 || useradd --system --home /var/lib/loraviewer-track --create-home loraviewer
install -d -o loraviewer -g loraviewer /var/lib/loraviewer-track

API_KEY=$(md instance/attributes/api-key)
LORA_BASE_URL=$(md instance/attributes/lora-base-url 2>/dev/null || echo "https://www.trailrouteview.com/user/jsal/gmap/")
umask 077
cat > /etc/loraviewer-track.env <<ENV
API_KEY=${API_KEY}
LORA_BASE_URL=${LORA_BASE_URL}
PORT=8080
DATA_DIR=/var/lib/loraviewer-track
ENV
umask 022

cat > /etc/systemd/system/loraviewer-track.service <<'UNIT'
[Unit]
Description=LoraViewer track server
After=network-online.target
Wants=network-online.target

[Service]
User=loraviewer
EnvironmentFile=/etc/loraviewer-track.env
ExecStart=/usr/bin/python3 /opt/loraviewer-track/trackserver.py
Restart=always
RestartSec=5
MemoryMax=400M

[Install]
WantedBy=multi-user.target
UNIT

IP=$(md instance/network-interfaces/0/access-configs/0/external-ip)
HOST="${IP//./-}.sslip.io"
cat > /etc/caddy/Caddyfile <<CADDY
${HOST} {
	encode gzip
	reverse_proxy 127.0.0.1:8080
}
CADDY
echo "https://${HOST}" > /var/lib/loraviewer-track/public-url

systemctl daemon-reload
systemctl enable --now loraviewer-track
systemctl restart loraviewer-track
systemctl enable caddy
systemctl restart caddy
