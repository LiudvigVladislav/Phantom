# ADR-006: Crypto Library Decision

Статус: **ACCEPTED**  
Дата: 2026-04-15

## Context

PHANTOM должен использовать только аудированные криптографические библиотеки (Product Doctrine 2.4).
Для реализации Signal Protocol (E2EE, Double Ratchet, prekeys) есть два основных кандидата:

### Кандидат A: signalapp/libsignal
- Официальная реализация Signal Protocol от Signal Foundation
- Ядро на Rust, обёртки для Java/Swift/TypeScript
- Лицензия: **AGPL-3.0** ⚠️

### Кандидат B: libsodium + ручная реализация Double Ratchet
- libsodium — ISC лицензия (безопасна для коммерческого использования)
- Базовые примитивы: X25519, XSalsa20-Poly1305, Ed25519
- Требует написания Double Ratchet поверх примитивов

### Кандидат C: BouncyCastle / Tink
- Google Tink — Apache-2.0, аудированная библиотека
- Не реализует Signal Protocol напрямую
- Может быть использован как слой примитивов

## Проблема с Кандидатом A (libsignal AGPL-3.0)

AGPL-3.0 — сильная копилефт-лицензия:
- Любая модификация libsignal должна быть открыта
- Линковка с AGPL-кодом в проприетарном приложении — юридически спорно
- Signal Foundation явно пишет: «использование вне Signal не поддерживается»
- API/bridge-слои могут меняться без предупреждения

**Требуется консультация юриста до интеграции.**

## Decision

✅ **ПРИНЯТО (2026-04-15) основателем:**

**PHANTOM будет open source (AGPL-3.0 совместим с проектом).**

- Использовать **libsignal-client** (signalapp/libsignal, AGPL-3.0)
- PHANTOM выпускается под open source лицензией (AGPL-3.0 или аналог)
- Монетизация: Premium-подписка, гранты (EFF, Mozilla, NLnet), Kickstarter, B2B — не продажа кода
- Открытый код = доверие пользователей = конкурентное преимущество для privacy-продукта

**Почему open source правильно для PHANTOM:**
- Signal, Telegram (клиент), Briar — все open source. Это норма для privacy мессенджеров
- Kickstarter и гранты (EFF/Mozilla/NLnet) охотнее дают open source проектам
- Пользователи, которые платят за приватность, доверяют коду, который можно проверить
- AGPL перестаёт быть проблемой, когда проект сам AGPL

**Libsodium** (ISC) используется параллельно как дополнительные примитивы там, где libsignal не покрывает.

## Consequences

- `core:crypto` реализуется через libsignal-client + libsodium
- Репозиторий будет публичным на GitHub под open source лицензией
- Лицензионный файл добавить при создании публичного репо
- Ни один агент не должен добавлять закрытые/проприетарные зависимости

## References

- [signalapp/libsignal](https://github.com/signalapp/libsignal) — AGPL-3.0
- [libsodium](https://libsodium.org) — ISC License
- [Google Tink](https://github.com/google/tink) — Apache-2.0
