# Threat Model v0

Статус: draft v0.1

## 1. Цель документа

Определить, от каких угроз PHANTOM защищает пользователей на раннем этапе,
какие угрозы считаются вне scope MVP, и какие инженерные меры обязательны до релиза Alpha-0 / MVP.

## 2. Активы, которые нужно защищать

### 2.1 Пользовательские активы
- содержимое сообщений;
- вложения и их ключи;
- контактные связи;
- identity и device keys;
- факт общения между участниками;
- список устройств пользователя;
- локальная история сообщений.

### 2.2 Системные активы
- корректность маршрутизации и доставки;
- доверенность публичных identity bundles;
- целостность relay storage semantics;
- устойчивость к abuse на публичных поверхностях;
- стабильность transport fallback.

## 3. Модель противника

### 3.1 Network observer
Может:
- видеть сетевой трафик;
- анализировать метаданные;
- блокировать отдельные endpoints или transport classes;
- применять DPI.

Не может:
- читать корректно зашифрованный payload без компрометации endpoints.

### 3.2 Malicious relay
Может:
- задерживать, дропать, повторно выдавать ciphertext blobs;
- анализировать объем, время, частоту обращений;
- пытаться связывать sender/recipient patterns.

Не должен мочь:
- читать plaintext;
- расшифровать messages;
- модифицировать ciphertext незаметно.

### 3.3 Compromised backend component
Может:
- отдавать ложные directory ответы;
- подменять connectivity hints;
- нарушать availability;
- логировать обращения.

### 3.4 Malicious user / scammer / spammer
Может:
- создавать много новых аккаунтов;
- делать mass outreach;
- отправлять фишинговые ссылки;
- пытаться злоупотреблять public discovery;
- атаковать relay квотами и flood.

### 3.5 Device thief / local compromise
Может:
- физически получить устройство;
- попытаться извлечь локальную БД;
- получить доступ к unlock state устройства.

### 3.6 Fake contact / impersonator
Может:
- копировать username/аватар/профиль;
- рассылать invite links;
- выдавать себя за trusted contact.

## 4. Приоритеты угроз

| Угроза | Вероятность | Влияние | Приоритет |
|---|---:|---:|---:|
| Компрометация private message content | Средняя | Критическое | P0 |
| Metadata leakage через backend/relay | Высокая | Высокое | P0 |
| Spam / scam через public discovery | Высокая | Высокое | P0 |
| Replay / duplicate delivery | Средняя | Среднее | P1 |
| Username impersonation | Высокая | Среднее | P1 |
| Relay denial / throttling | Средняя | Среднее | P1 |
| Device loss без надлежащего local protection | Средняя | Высокое | P1 |
| Полный отказ transport path | Средняя | Высокое | P1 |
| Полноценная глобальная censorship-resistance | Высокая | Высокое | P2 для MVP |

## 5. Обязательные меры защиты для Alpha-0

### 5.1 Message confidentiality
- end-to-end encryption через проверенную библиотеку;
- локальное secure key storage;
- encrypted local DB;
- строгая модель session state.

### 5.2 Relay minimization
- relay хранит только ciphertext;
- relay не должен получать plaintext metadata шире минимально необходимого envelope;
- TTL обязателен;
- fetch/delete semantics должны быть четко определены.

### 5.3 Identity integrity
- invite links должны содержать верифицируемую identity information;
- session establishment должно проверять identity bundle;
- должен существовать safety number / verification primitive.

### 5.4 Abuse control
- базовые rate limits для new accounts;
- message requests для first contact;
- квоты на relay usage;
- ограничение слишком широкого discovery на MVP.

### 5.5 Local device protection
- encrypted at rest;
- ключи в platform secure storage;
- возможность wipe session state при logout / reset.

## 6. Что пока вне scope MVP

- защита от fully compromised endpoint после unlock;
- идеальная анонимность уровня state-grade adversary;
- полная защита от traffic analysis;
- production-grade decentralized governance of directory;
- anti-abuse для huge public communities и channels.

## 7. Security invariants

- Backend compromise не должен раскрывать plaintext.
- Relay compromise не должен раскрывать plaintext.
- Discovery compromise не должен автоматически раскрывать message history.
- Смена транспорта не должна менять криптографические гарантии.
- New account не должен получать неограниченный outreach по умолчанию.

## 8. Open risks requiring later design

- Мульти-девайс trust graph;
- recovery without central authority;
- phone-based discovery without privacy regression;
- safe public channels architecture;
- bridge / obfuscation transport abuse vectors.
