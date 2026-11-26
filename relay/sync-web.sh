#!/bin/sh
# Копирует веб-клиент из Android-приложения в проект Vercel.
# index.html у реле свой: в нём адрес реле и пути без /assets.
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SRC="$DIR/../app/src/main/assets/web"
cp "$SRC/app.js" "$DIR/public/app.js"
cp "$SRC/style.css" "$DIR/public/style.css"
mkdir -p "$DIR/public/assets"
cp "$SRC/cat.png" "$DIR/public/assets/cat.png"
cp "$SRC/cat-small.png" "$DIR/public/assets/cat-small.png"
echo "Веб-клиент синхронизирован в relay/public"
