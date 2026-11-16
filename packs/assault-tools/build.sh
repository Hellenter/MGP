#!/bin/sh
# Перерисовать текстуры (Python 3 + Pillow) и пересобрать packs/assault-tools.zip.
set -e
cd "$(dirname "$0")"
OUT=assets/minecraft/textures/item
for s in axe pick shovel; do (cd src && python3 "$s.py" >/dev/null); done
mv -f src/diamond_*.png "$OUT/"
rm -f ../assault-tools.zip
# фиксированные даты, чтобы SHA-1 не менялся от пересборки
find pack.mcmeta assets -exec touch -t 202601010000 {} +
zip -X -r -9 ../assault-tools.zip pack.mcmeta assets >/dev/null
echo "packs/assault-tools.zip: $(sha1sum ../assault-tools.zip | cut -d' ' -f1)"
