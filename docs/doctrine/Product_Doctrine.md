# Product Doctrine

Status: draft v0.1
Owner: Founder
Binding on every architectural and product decision

---

## English summary

PHANTOM follows seven product invariants, each binding on every
architectural and product decision in this repository: (1)
end-to-end encryption is non-negotiable — private chats and
small private groups are an inviolable encrypted zone; (2)
phone numbers are optional, never identity-load-bearing; (3)
the public discovery layer is separable from the encrypted
core, so the public surface can be moderated without ever
touching private content; (4) no custom cryptography — only
audited primitives (libsodium, Signal-protocol-derived ratchet
constructions); (5) the transport layer is replaceable — the
client must work over direct WSS, Tor, Xray, and any future
pluggable transport without architectural change; (6) the app
degrades gracefully under network restrictions rather than
failing closed; (7) anti-scam protection is by design (Trust
Tier flow, Message Requests funnel, no public unsolicited
messaging surface) rather than added on as a moderation layer.
The full doctrine in Russian follows below.

---

## 1. Миссия

PHANTOM — это приватный и устойчивый мессенджер для повседневного использования,
который не зависит от одной точки отказа и не требует компромисса между удобством и безопасностью.

## 2. Главные продуктовые инварианты

### 2.1 Private chats sacred
Личные чаты и малые приватные группы — это зона, где:
- содержимое должно быть end-to-end encrypted;
- ключи не должны покидать устройства пользователей;
- backend не должен иметь доступа к plaintext;
- moderation не должна требовать чтения личной переписки по умолчанию.

### 2.2 Phone number optional
Телефон не является обязательной основой идентичности.
Пользователь должен иметь возможность пользоваться системой через:
- username;
- invite link;
- QR / public identity bundle.

### 2.3 Public layer is separable
Публичные поверхности продукта должны быть отделены от приватного слоя.
Сюда относятся:
- search;
- discovery;
- публичные профили;
- каналы и будущие community surfaces;
- reputation / verification / anti-spam.

### 2.4 No custom cryptography
PHANTOM не пишет собственную криптографию. Допускаются только:
- аудированные библиотеки;
- общеизвестные и проверенные primitives;
- узкие адаптеры вокруг таких библиотек.

### 2.5 Transport must be replaceable
Механизм доставки сообщений не должен быть жестко вшит в application logic.
Система обязана поддерживать transport abstraction.

### 2.6 Graceful degradation over binary failure
Цель проекта — не абсолютная «магическая неуязвимость», а система,
которая деградирует мягко и сохраняет хотя бы часть функции при ограничениях.

### 2.7 Anti-scam by product design
Анти-абьюз и анти-скам не должны строиться на чтении private messages.
Они должны проектироваться через:
- policy layer;
- rate limits;
- message requests;
- trust tiers;
- verified surfaces;
- restricted reach for new accounts.

## 3. Чего PHANTOM не делает на старте

На MVP проект не пытается быть:
- супер-приложением;
- заменой Telegram по всей экосистеме;
- платежной платформой;
- маркетплейсом;
- бот-платформой полного цикла.

## 4. Инженерные инварианты

- Сначала одна законченная вертикаль, затем расширение.
- Каждое решение должно иметь явные trust boundaries.
- Любой сетевой компонент считается потенциально наблюдаемым или компрометируемым.
- Локальное шифрованное хранение — обязательный базовый слой.
- UI не должен содержать бизнес-логику транспорта и криптографии.
- Все критические state transitions должны быть описаны как state machines.

## 5. MVP-цель

Alpha-0 = два устройства могут:
- создать identity;
- обменяться invite link;
- установить session;
- отправить E2EE text message;
- доставить сообщение через relay fallback;
- сохранить историю локально в encrypted storage.

Если это не работает стабильно — расширение scope запрещено.

## 6. Критерии отказа от фичи

Фича откладывается или вырезается из MVP, если:
- ломает private-by-default модель;
- требует хранения plaintext на backend;
- увеличивает поверхность abuse раньше, чем есть policy layer;
- увеличивает архитектурную сложность без завершения основного chat core.

## 7. Открытые вопросы

- Как именно оформлять multi-device trust model?
- Нужен ли hosted directory backend до полноценного distributed backend?
- Какие transport-режимы обязательны в Alpha-0, а какие later-phase?
- Какой минимальный anti-spam baseline нужен уже на раннем этапе?
