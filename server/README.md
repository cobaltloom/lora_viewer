# LoraViewer track server

A small always-on server that collects glider positions from OGN and LoRa and keeps the last
24 hours, so the app can show each flying glider's trail from takeoff — including the part
flown before the app was opened. See the docstring in `trackserver.py` for the API.

Runs on a Google Cloud **Always Free e2-micro** VM in the Firebase project `loramenuma`.

## Files

- `trackserver.py` — the server (Python 3 standard library only)
- `test_trackserver.py` — unit tests: `cd server && python3 -m unittest test_trackserver`
- `deploy/startup.sh` — VM startup script: installs Caddy (HTTPS) and runs the server as a service

## First-time setup (Google Cloud Shell)

Prerequisite: the `loramenuma` project must have a billing account linked (required even for
the free tier). Set a budget alert (e.g. ¥100) under Billing → Budgets & alerts.

Easiest: open Cloud Shell from https://console.cloud.google.com (the `>_` button top right),
upload `trackserver.py`, `deploy/startup.sh` and `deploy/create.sh` (⋮ menu → Upload), and run
`bash create.sh` - it does everything below and prints the two Firestore values.

Or step by step, after uploading `trackserver.py` and `deploy/startup.sh`:

```sh
gcloud config set project loramenuma
gcloud services enable compute.googleapis.com

# Random key the app sends with each request; keep it for the Firestore step below.
API_KEY=$(openssl rand -hex 16); echo "API_KEY=$API_KEY"

# Free tier: e2-micro in us-west1 / us-central1 / us-east1, 30 GB standard disk max.
gcloud compute instances create loraviewer-track \
  --zone=us-west1-b --machine-type=e2-micro \
  --image-family=debian-12 --image-project=debian-cloud \
  --boot-disk-size=10GB --boot-disk-type=pd-standard \
  --tags=loraviewer-track \
  --metadata=api-key=$API_KEY \
  --metadata-from-file=startup-script=startup.sh,trackserver-py=trackserver.py

gcloud compute firewall-rules create loraviewer-track-web \
  --allow=tcp:80,tcp:443 --target-tags=loraviewer-track

# After ~2 minutes: prints https://<ip>.sslip.io
gcloud compute ssh loraviewer-track --zone=us-west1-b --command='cat /var/lib/loraviewer-track/public-url'
```

Check it: open `https://<ip>.sslip.io/health` in a browser — it should show
`{"ok":true,"gliders":{...}}` (counts grow as positions arrive).

Then in the Firebase console → Firestore → `meta/serverConfig`, add:

- `trackServerUrl` (string): `https://<ip>.sslip.io`
- `trackServerKey` (string): the `API_KEY` printed above

The apps start using the server on their next launch. Delete `trackServerUrl` to turn the
feature off again.

## Updating the server code

Upload the new `trackserver.py` to Cloud Shell, then:

```sh
gcloud compute instances add-metadata loraviewer-track --zone=us-west1-b \
  --metadata-from-file=trackserver-py=trackserver.py
gcloud compute instances reset loraviewer-track --zone=us-west1-b
```

## Notes

- The external IP is ephemeral: it survives reboots/resets but changes if the VM is stopped and
  started. If it changes, read the new URL as above and update `trackServerUrl` in Firestore.
- Logs: `gcloud compute ssh loraviewer-track --zone=us-west1-b --command='journalctl -u loraviewer-track -n 50'`
- OGN data usage rules apply: data is kept 24 h only, and aircraft that are stealth / no-tracking /
  random-address or marked not tracked / not identified in the OGN device database are never stored.
