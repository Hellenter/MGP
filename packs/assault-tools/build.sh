#!/bin/sh
# Пересобрать packs/assault-tools.zip. Нужны Python 3 + Pillow и клиент Minecraft (jar) для ванильных силуэтов:
#   ./build.sh ~/.minecraft/versions/26.3/26.3.jar
set -e
cd "$(dirname "$0")"
OUT=assets/minecraft/textures/item
[ -n "$1" ] && python3 src/recolor.py "$1" "$OUT"
rm -f ../assault-tools.zip
# фиксированные даты, чтобы SHA-1 не менялся от пересборки
find pack.mcmeta assets -exec touch -t 202601010000 {} +
zip -X -r -9 ../assault-tools.zip pack.mcmeta assets >/dev/null
echo "packs/assault-tools.zip: $(sha1sum ../assault-tools.zip | cut -d' ' -f1)"
