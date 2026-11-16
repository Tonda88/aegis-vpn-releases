# Aegis VPN Desktop (Windows, MVP)

Десктопная версия (Compose Desktop/JVM) для Windows: управление серверами (VLESS, Shadowsocks, WireGuard), импорт ключей/ссылок и подписок `https://`, подключение через `sing-box`.

## Требования
- Windows 10/11 x64
- Java 17+ (JRE/JDK) для запуска JAR, либо собранный дистрибутив
- `sing-box.exe` (Windows amd64) — положите рядом с приложением/в `tools/` или используйте bundled из сборки
- Для режима **TUN** (системный VPN) обычно требуется запуск **от имени администратора** (и при необходимости `wintun.dll` рядом с `sing-box.exe`, в зависимости от окружения)

## Возможности MVP
- Добавление ключей `vless://`, `ss://`, `wg://`, WireGuard `.conf`, подписок `https://`
- Список/выбор/удаление серверов
- Подключение/отключение (запуск `sing-box run -c config.json`)
- Генерация конфига sing-box 1.14.x: inbound `tun` + локальный прокси `mixed` `127.0.0.1:2080`

## Сборка
```powershell
$env:JAVA_HOME="C:\Users\админ\AppData\Local\Temp\opencode\toolchain\jdk-17.0.20.1+1"
.\gradlew.bat :packageUberJarForCurrentOS
```
Артефакт: `build\compose\jars\AegisVPN-Desktop-windows-x64-1.0.0.jar` (имя может отличаться).

## Запуск
```powershell
java -jar .\build\compose\jars\AegisVPN-Desktop-windows-x64-1.0.0.jar
```
Данные профилей: `%APPDATA%\AegisVPN-Desktop\profiles.json`.

## Примечания
- Это MVP десктоп-клиент. Поведение TUN в Windows зависит от прав/драйверов; при проблемах можно использовать локальный прокси `127.0.0.1:2080`.
- Конфиги/лог: `%APPDATA%\AegisVPN-Desktop\config.json`, `sing-box.log`.
