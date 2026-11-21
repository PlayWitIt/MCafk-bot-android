#!/usr/bin/env bash
# Runs the app's bot code on a PC (Linux/macOS, Java 17+). Usage:
#   desktop/run.sh live [server]     real server + your Microsoft account, interactive (see LiveTest.java)
#   desktop/run.sh real <script>     scripted tests against the local vanilla server from desktop/realserver.sh
set -e
cd "$(dirname "$0")/.."
LIB=desktop/lib; OUT=desktop/build
mkdir -p "$LIB" "$OUT"
[ -f $LIB/json.jar ] || curl -sfL -o $LIB/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
[ -f $LIB/rhino.jar ] || curl -sfL -o $LIB/rhino.jar https://repo1.maven.org/maven2/org/mozilla/rhino/1.7.15/rhino-1.7.15.jar
CP=$LIB/json.jar:$LIB/rhino.jar
S=app/src/main/java/app/afkbot
rm -rf $OUT && mkdir -p $OUT
javac -nowarn -encoding UTF-8 --release 8 -cp $CP -d $OUT $(find desktop/stubs -name '*.java') \
  $S/{Bot,Buf,Text,Proto,Hub,World,MapData,ModEngine,ProtoDef,Inv,Shapes,Auth,RemoteZip,TexturePack,GameState,GameLight,GameMesher,GameAssets,GameAtlas,GameModels,GameIcons}.java desktop/src/app/afkbot/*.java 2>&1 \
  | grep -v "^Note:\|warning\|^[0-9]* warning" || true
mode=${1:-live}; shift || true
case "$mode" in
  live) exec java -cp $OUT:$CP app.afkbot.LiveTest "$@" ;;
  real) exec java -Dafk.cmd=desktop/realsrv/cmd -cp $OUT:$CP app.afkbot.RealTest "$@" ;;
  preview) exec java -Xmx1500m -Dafk.cmd=desktop/realsrv/cmd -cp $OUT:$CP app.afkbot.PreviewTest "$@" ;;
  *) echo "usage: desktop/run.sh live [server] | real <walk|far|course|physics|diag|copper|shulker> | preview [outDir]"; exit 1 ;;
esac
