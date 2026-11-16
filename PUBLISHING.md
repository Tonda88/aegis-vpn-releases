# Публикация Aegis VPN в `Tonda88/aegis-vpn-releases`

Публичный репозиторий релизов: https://github.com/Tonda88/aegis-vpn-releases  
Приложение (1.3.2+) проверяет обновления по файлу:
`https://raw.githubusercontent.com/Tonda88/aegis-vpn-releases/main/latest.json`

> `git`/`gh` на машине сборки не требуются — всё можно сделать через веб-интерфейс GitHub.

## 1. Создать публичный репозиторий (один раз)
1. На GitHub: **New repository** → Owner: `Tonda88`, Name: `aegis-vpn-releases`, Visibility: **Public**.
2. Можно добавить README при создании (чтобы появилась ветка `main`).

## 2. Опубликовать релиз 1.3.2
1. В репозитории откройте **Releases → Draft a new release**.
2. **Tag:** `v1.3.2` (create new tag on `main`). **Title:** `Aegis VPN 1.3.2`.
3. В описание добавьте changelog (можно взять из `latest.json` / README).
4. **Assets** — загрузите файлы из `AegisVPN\APK\` и checksums:
   - `AegisVPN-1.3.2-arm64-v8a.apk`
   - `AegisVPN-1.3.2-armeabi-v7a.apk`
   - `CHECKSUMS.txt` (опционально, для удобства)
5. Нажмите **Publish release**.

Важно: имена файлов и тег должны совпадать с URL в `latest.json`:
- `.../releases/download/v1.3.2/AegisVPN-1.3.2-arm64-v8a.apk`
- `.../releases/download/v1.3.2/AegisVPN-1.3.2-armeabi-v7a.apk`

## 3. Загрузить `latest.json` в ветку `main`
1. В корне репозитория нажмите **Add file → Upload files** (или **Create new file**).
2. Загрузите/вставьте содержимое файла `AegisVPN\latest.json` (из этой папки сборки) как `latest.json` в корень ветки `main`.
3. Commit в `main`.

После этого установленное приложение 1.3.2 сможет увидеть следующие обновления (при увеличении `versionCode`/версии и публикации нового релиза + обновлении `latest.json`).

## 4. Как выпускать следующие обновления
1. Собрать новую версию (увеличить `versionCode` и `versionName` в `app/build.gradle.kts`).
2. Создать новый GitHub Release с новым тегом (например `v1.3.3`) и загрузить новые APK с именами, содержащими новую версию.
3. Обновить `latest.json` в `main`: `versionCode`, `versionName`, `notes`, `arm64Url/sha256`, `armeabiV7aUrl/sha256` под новый релиз.

## Формат `latest.json`
```json
{
  "versionCode": 7,
  "versionName": "1.3.2",
  "notes": "Описание изменений",
  "arm64Url": "https://github.com/Tonda88/aegis-vpn-releases/releases/download/v1.3.2/AegisVPN-1.3.2-arm64-v8a.apk",
  "arm64Sha256": "<sha256 arm64 apk>",
  "armeabiV7aUrl": "https://github.com/Tonda88/aegis-vpn-releases/releases/download/v1.3.2/AegisVPN-1.3.2-armeabi-v7a.apk",
  "armeabiV7aSha256": "<sha256 v7a apk>"
}
```
Приложение выбирает APK по ABI устройства (arm64-v8a / armeabi-v7a) и проверяет SHA-256 перед установкой.

## Установка обновлений на устройстве
- В приложении: **Настройки → Обновления → Проверить обновления** (также есть автопроверка при запуске).
- При первом обновлении Android может попросить разрешить **установку из этого источника** для Aegis VPN.
- Обновление устанавливается поверх старой версии, так как APK подписаны тем же release-ключом.
