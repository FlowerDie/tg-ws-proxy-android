# TG WS Proxy — Android

Нативное Android-приложение — MTProto WebSocket прокси для Telegram.

## Что это?

Приложение создаёт локальный MTProto-прокси на твоём Android-устройстве, который:
- Перехватывает подключения Telegram
- Перенаправляет трафик через WebSocket (WSS) к серверам Telegram
- Использует AES-CTR шифрование
- Работает в фоне как foreground service

## Как получить APK

### Способ 1: GitHub Actions (рекомендуется)

1. **Создай репозиторий на GitHub:**
   - Открой https://github.com
   - Нажми **"New"** (зелёная кнопка)
   - Введи название (например `tg-ws-proxy-android`)
   - Нажми **"Create repository"**

2. **Загрузи файлы на GitHub:**

   **Вариант A — через GitHub Desktop:**
   - Скачай с https://desktop.github.com
   - Установи и залогинься
   - Нажми **File → Add Local Repository**
   - Выбери папку `C:\Users\Flower\Desktop\proj\5\tg-ws-proxy-android`
   - Напиши комментарий (например "Initial commit")
   - Нажми **"Commit to main"**
   - Нажми **"Push origin"**

   **Вариант B — через командную строку:**
   ```bash
   cd C:\Users\Flower\Desktop\proj\5\tg-ws-proxy-android
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/ТВОЙ_АККАУНТ/tg-ws-proxy-android.git
   git push -u origin main
   ```

3. **Скачай APK:**
   - Перейди на вкладку **Actions** в своём репозитории
   - Подожди 5-10 минут пока сборка завершится
   - Нажми на последний workflow run
   - Внизу страницы нажми **tg-ws-proxy-android**
   - Скачай `tg-ws-proxy-android.zip` и распакуй APK

### Способ 2: Локальная сборка (если есть JDK + Android SDK)

```bash
cd C:\Users\Flower\Desktop\proj\5\tg-ws-proxy-android
gradlew.bat assembleRelease
```

APK будет в `app\build\outputs\apk\release\`

## Установка

1. Скопируй APK на телефон
2. Открой файл (разреши установку из неизвестных источников)
3. Установи приложение

## Настройка в Telegram

1. Открой приложение TG WS Proxy
2. Нажми **"Запустить прокси"**
3. В Telegram перейди в **Настройки → Продвинутые → Тип подключения → Прокси**
4. Добавь новый прокси:
   - **Тип:** MTProto
   - **Сервер:** `127.0.0.1`
   - **Порт:** `1443` (или тот что указал в приложении)
   - **Секрет:** скопируй из приложения (или оставь сгенерированный)

## Настройки приложения

| Параметр | По умолчанию | Описание |
|----------|-------------|----------|
| Хост | `127.0.0.1` | Адрес для прослушивания |
| Порт | `1443` | Порт прокси |
| Секрет |自动生成 | 32 hex символа для авторизации |
| DC 2 IP | Авто | IP для DC 2 (оставь пустым для авто) |
| DC 4 IP | Авто | IP для DC 4 (оставь пустым для авто) |

## Как работает

```
Telegram Desktop/Mobile
         ↓
   127.0.0.1:1443 (TG WS Proxy)
         ↓
   MTProto Handshake + AES-CTR
         ↓
   WebSocket (WSS) или TCP fallback
         ↓
   Telegram Data Centers (kws2.web.telegram.org и т.д.)
```

## Особенности

- ✅ Foreground service (работает в фоне)
- ✅ AES-CTR шифрование
- ✅ MTProto handshake
- ✅ WebSocket + TCP fallback
- ✅ Настройка через UI
- ✅ Статистика трафика
- ✅ Тёмная тема

## Требования

- Android 7.0+ (API 24)
- Разрешение на уведомления (Android 13+)

## Лицензия

MIT License
