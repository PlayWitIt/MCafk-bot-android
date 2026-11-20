#!/usr/bin/env bash
# Starts a local vanilla 1.21.11 server (offline mode, port 25601) for desktop/run.sh real <script>.
# Console commands can be written to desktop/realsrv/cmd (a FIFO), which is how RealTest sets up test blocks.
set -e
cd "$(dirname "$0")"
mkdir -p realsrv && cd realsrv
if [ ! -f server.jar ]; then
  url=$(curl -sf https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | python3 -c "import json,sys;print([v['url'] for v in json.load(sys.stdin)['versions'] if v['id']=='1.21.11'][0])")
  jar=$(curl -sf "$url" | python3 -c "import json,sys;print(json.load(sys.stdin)['downloads']['server']['url'])")
  curl -sfL -o server.jar "$jar"
fi
echo "eula=true" > eula.txt
cat > server.properties <<P
online-mode=false
server-port=25601
level-type=minecraft\:flat
spawn-protection=0
difficulty=peaceful
motd=Local test
P
rm -f cmd && mkfifo cmd
(tail -f cmd | java -Xmx2G -jar server.jar nogui > server.log 2>&1 &)
for i in $(seq 1 90); do grep -q "Done (" server.log 2>/dev/null && { echo "server ready on 127.0.0.1:25601"; exit 0; }; sleep 2; done
echo "server didn't start - see desktop/realsrv/server.log"; exit 1
