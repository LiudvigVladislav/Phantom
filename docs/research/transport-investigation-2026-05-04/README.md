# Transport Investigation 2026-05-04

## Цель

Найти настоящее решение для transport reliability на aggressive-OEM
Android И на нестабильных мобильных сетях БЕЗ требования VPN.

## Триггер

4-test matrix 2026-05-04 (Tecno Spark Go + Pixel emulator) показал:

| Test | VPN config | Phone reconnect | Emu reconnect | Voice |
|---|---|---|---|---|
| 1 | None | 60с | ~50с (РЕГРЕССИЯ) | ❌ |
| 2 | VPN on PC | 60с | Стабилен | ❌ |
| 3 | VPN on phone | Стабилен | ~50с | DEFERRED |
| 4 | VPN on both | Стабилен | Стабилен | ✅ всё работает |

**Ключевое:** даже стоковый эмулятор без VPN получает `Connection reset`
каждые 50 секунд от relay (с PING_INTERVAL_MS=3000). Это НЕ
Tecno-firmware-specific.

## Гипотезы для проверки

H1. CGN timeout (мобильный ISP закрывает idle TCP entries)
H2. DPI/transparent proxy interference
H3. Caddy/relay server-side timeout или rate limit
H4. OkHttp/Ktor client misconfiguration
H5. MTU mismatch без VPN
H6. Что-то ещё что мы не учли

## Структура research

| Файл | Что | Кто |
|---|---|---|
| `01-server-audit.md` | Caddyfile + relay code audit, timeouts, rate limits | Agent A |
| `02-industry-research.md` | Как Signal/WhatsApp/Element/SimpleX/Briar решают same problem | Agent B |
| `03-client-transport-audit.md` | Ktor/OkHttp WebSocket config vs best practices | Agent C |
| `04-network-diagnostic-plan.md` | План tcpdump/wireshark экспериментов на relay | Agent D |
| `99-synthesis.md` | Сводный документ + ranked hypotheses + recommended fix | После всех |

## Status

- 2026-05-04: Phase 1 research kicked off, 4 agents in parallel
- Test data: см. логи в чате (8 файлов test-*.log)
- Current PING_INTERVAL_MS: 3000ms (experimental, breaks emu) — ожидает решения revert или нет

## Decisions log

- 2026-05-02: ADR-013 диагностировал проблему как Tecno HiOS firmware radio parking
- 2026-05-04: 4-test matrix опровергает эту диагностику — эмулятор тоже affected
- 2026-05-04: PR 1 (data integrity) ON HOLD до решения transport
- 2026-05-04: PR 3 (voice chunking) merged — код корректен (Test 4 proves)
