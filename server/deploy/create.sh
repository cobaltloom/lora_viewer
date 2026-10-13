#!/bin/bash
# One-shot setup for Google Cloud Shell: creates the track server VM and prints the two values to
# put in Firestore. Upload this file, startup.sh and trackserver.py to Cloud Shell, then run:
#   bash create.sh
set -euo pipefail

PROJECT=loramenuma
ZONE=us-west1-b
VM=loraviewer-track

for f in startup.sh trackserver.py; do
  [ -f "$f" ] || { echo "エラー: $f が見つかりません。3つのファイルを同じ場所にアップロードしてください。"; exit 1; }
done

gcloud config set project "$PROJECT" >/dev/null
echo "== Compute Engine を有効化しています(数分かかることがあります)"
gcloud services enable compute.googleapis.com

if gcloud compute instances describe "$VM" --zone="$ZONE" >/dev/null 2>&1; then
  echo "== サーバーは作成済みです。鍵を読み出します"
  API_KEY=$(gcloud compute instances describe "$VM" --zone="$ZONE" \
    --format='value(metadata.items.api-key)')
else
  API_KEY=$(openssl rand -hex 16)
  echo "== サーバーを作成しています(無料枠: e2-micro / $ZONE)"
  gcloud compute instances create "$VM" \
    --zone="$ZONE" --machine-type=e2-micro \
    --image-family=debian-12 --image-project=debian-cloud \
    --boot-disk-size=10GB --boot-disk-type=pd-standard \
    --tags=loraviewer-track \
    --metadata=api-key="$API_KEY" \
    --metadata-from-file=startup-script=startup.sh,trackserver-py=trackserver.py
fi

if ! gcloud compute firewall-rules describe loraviewer-track-web >/dev/null 2>&1; then
  echo "== ファイアウォール(80/443番)を開けています"
  gcloud compute firewall-rules create loraviewer-track-web \
    --allow=tcp:80,tcp:443 --target-tags=loraviewer-track
fi

IP=$(gcloud compute instances describe "$VM" --zone="$ZONE" \
  --format='value(networkInterfaces[0].accessConfigs[0].natIP)')
URL="https://${IP//./-}.sslip.io"

echo "== サーバーの起動を待っています(最大10分)"
for i in $(seq 1 60); do
  if curl -fsS --max-time 5 "$URL/health" >/dev/null 2>&1; then
    echo
    echo "完了しました。Firebase の meta/serverConfig に次の2つを追加してください:"
    echo
    echo "  trackServerUrl (string) : $URL"
    echo "  trackServerKey (string) : $API_KEY"
    echo
    echo "動作確認: ブラウザで $URL/health を開くと {\"ok\":true,...} と表示されます。"
    exit 0
  fi
  printf "."
  sleep 10
done
echo
echo "10分待っても応答がありません。このメッセージのスクショを送ってください。"
exit 1
