#!/bin/bash
set -e
echo "=========================================================="
echo "  Автоматичне завантаження на GitHub та запуск збірки APK "
echo "=========================================================="
read -p "Вставте посилання на ваш порожній GitHub репозиторій (наприклад https://github.com/user/repo.git): " REPO_URL
git init
git checkout -B main
git add -A
git commit -m "Build Mac Magic Trackpad Android APK"
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"
git push -u origin main --force
echo "✅ Успішно завантажено! Відкрийте вкладку Actions у вашому репозиторії на GitHub — за 1.5 хвилини там з'явиться готовий MacMagicTrackpad-APK!"
