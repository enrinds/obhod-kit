# Сторонние компоненты и их лицензии

Приложение собирается из открытого кода других авторов. Ниже — кто, что и под какой лицензией.
Полные тексты лицензий лежат в папке [`licenses/`](licenses/).

---

## 1. Движки внутри APK

| Компонент | Автор | Лицензия | Что именно используется | Текст лицензии |
|---|---|---|---|---|
| **byedpi** (ciadpi) | [hufrea](https://github.com/hufrea/byedpi) | MIT | Скомпилированное ядро `libbyedpi.so`, обход DPI | `licenses/byedpi-MIT.txt` |
| **hev-socks5-tunnel** | [heiher](https://github.com/heiher/hev-socks5-tunnel) | MIT | TUN → SOCKS5 мост | `licenses/hev-socks5-tunnel-MIT.txt` |
| **tg-ws-proxy** | [Flowseal](https://github.com/Flowseal/tg-ws-proxy) | MIT | Идея и протокол MTProto-over-WebSocket для Telegram | `licenses/tg-ws-proxy-MIT.txt` |
| **tg-ws-proxy-android** | [amurcanov](https://github.com/amurcanov/tg-ws-proxy-android) | GPL-3.0 | Rust-движок `libtgwsproxy.so` | `licenses/GPL-3.0.txt` |
| **sing-box** | [nekohasekai / SagerNet](https://github.com/SagerNet/sing-box) | GPL-3.0 + доп. условие | Ядро VPN (VLESS/VMess/Trojan/SS/Hysteria2) | `licenses/sing-box-GPL3-with-name-clause.txt` |

## 2. Оболочка приложения

| Компонент | Автор | Лицензия | Текст лицензии |
|---|---|---|---|
| **ByeByeDPI (Android)** | [romanvht](https://github.com/romanvht/ByeByeDPI) | GPL-3.0 | `licenses/GPL-3.0.txt` |
| **ByeByeDPI × TG** (используется как база форка) | [EwenLoy](https://github.com/EwenLoy/ByeByeDPI-x-tg) | GPL-3.0 | `licenses/GPL-3.0.txt` |

Исходный код оболочки — 69 Kotlin-файлов: активности, службы, настройки, тесты стратегий, списки
доменов, Quick Settings Tile, автозапуск.

---

## Что важно знать про лицензии (по-человечески)

### MIT (byedpi, hev-socks5-tunnel, tg-ws-proxy)

Можно копировать, менять и распространять даже в закрытом виде. **Единственное требование** —
сохранить текст лицензии и строку копирайта. Именно поэтому файлы в `licenses/` лежат в репозитории
и упоминаются в README.

### GPL-3.0 (ByeByeDPI, sing-box, tg-ws-proxy-android)

Можно копировать и менять, **но**:

1. Производная работа тоже распространяется под GPL-3.0.
2. Если вы выкладываете APK — вы обязаны выложить **исходный код** (этот репозиторий и есть исходники).
3. Нельзя убирать копирайты и менять лицензию на «свою закрытую».
4. Нельзя выдавать чужой код за полностью свой.

Это не формальность: именно несоблюдение GPL — самая частая причина жалоб и сносов форков.
Соблюдение GPL, наоборот, делает претензию «вы украли код» бессмысленной.

### Дополнительное условие sing-box

В лицензии sing-box прямо написано:

> *In addition, no derivative work may use the name or imply association with this application
> without prior consent.*

То есть:

- ❌ нельзя назвать своё приложение «sing-box», «sing-box client», «SagerNet» и т.п.;
- ❌ нельзя намекать, что это официальное приложение SagerNet;
- ✅ можно честно писать: «использует ядро sing-box (GPL-3.0)».

### Названия и логотипы

Торговые марки лицензией не покрываются. Поэтому:

- не используйте логотипы и названия авторов в оформлении своего приложения;
- не называйте форк «ByeByeDPI» — у него уже есть автор и официальный сайт;
- в README честно пишите: «форк такого-то проекта», «вдохновлено таким-то».

---

## Чего «указание авторов» не решает

- **DMCA-жалоба** возможна всегда: GitHub снимает репозиторий по заявлению, а разбирательство идёт
  потом. Защита — корректные лицензии и уважение авторов, а не их отсутствие.
- **GitHub Terms of Service** запрещают вредоносное ПО; обход блокировок под это не подпадает,
  но любая подозрительная «сборка» может попасть в фильтры.
- **Локальное законодательство** (в РФ — ограничения на рекламу/популяризацию средств обхода) от
  лицензий не зависит вообще. Это отдельный риск, см. `docs/security.md` в соседнем проекте.

## Исходники движков в репозитории

Чтобы сборка не зависела от git-сабмодулей, исходники вложены прямо в репозиторий:

| Что | Путь | Коммит |
|---|---|---|
| byedpi | `app/src/main/cpp/byedpi/` | `ba532298de7b28cfe854aea83d061369d13ca290` |
| hev-socks5-tunnel | `app/src/main/jni/hev-socks5-tunnel/` | `4d6c334dbfb68a79d1970c2744e62d09f71df12f` |

Файл `.gitmodules` удалён: `submodules: recursive` в CI больше ничего не качает, а сборка
работает даже из обычного zip-архива. Лицензии обоих движков — MIT, тексты лежат в
`licenses/` и в самих папках.


## Вложенные сабмодули hev-socks5-tunnel

У `hev-socks5-tunnel` есть собственные сабмодули. Без них NDK-сборка падает с ошибкой
`Module hev-socks5-tunnel depends on undefined modules: yaml lwip hev-task-system`.
Поэтому они тоже вложены в репозиторий (каталог `app/src/main/jni/hev-socks5-tunnel/`):

| Компонент | Путь | Коммит | Лицензия |
|---|---|---|---|
| hev-socks5-core | `src/core/` | `4be2e62` | MIT |
| hev-task-system | `third-part/hev-task-system/` | `8d83bbb` | MIT |
| lwip | `third-part/lwip/` | `07dbf16` | BSD-3-Clause |
| yaml | `third-part/yaml/` | `efa3611` | MIT |

Тексты лицензий — в `licenses/`. Проверено локально: `ndk-build` с NDK r27b собирает
`libhev-socks5-tunnel.so` под 4 ABI без ошибок.

## Как обновлять сторонние компоненты

1. `byedpi` — обновить сабмодуль/исходники в форке и заголовки в `licenses/byedpi-MIT.txt` (год
   копирайта меняется только если автор его поменяет).
2. `sing-box` — поднять `SINGBOX_VERSION` в `tools/fetch-singbox.sh` и прогонить сборку.
3. `tg-ws-proxy-android` — пересобрать `libtgwsproxy.so` (Rust + cargo-ndk) или взять из апстрима
   `amurcanov/tg-ws-proxy-android`, если версия обновилась.
4. Всегда проверять, не поменял ли автор лицензию: строка `In addition…` у sing-box уже менялась
   по смыслу, поэтому текст лицензии хранится файлом, а не пересказом.
