#!/bin/sh
# Копирует веб-клиент из Android-приложения в проект Vercel.
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cp "$DIR/../app/src/main/assets/web/app.js" "$DIR/public/app.js"
cp "$DIR/../app/src/main/assets/web/style.css" "$DIR/public/style.css"
echo "Веб-клиент синхронизирован в relay/public"
