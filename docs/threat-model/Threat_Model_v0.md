# Threat Model v0

Status: draft v0.1
Английский executive summary — основной документ ниже на русском.
The English executive summary is at the top; the formal threat
model body is in Russian below it.

---

## English executive summary

PHANTOM is a privacy-first messenger built for users on networks
they cannot trust. This document defines what we defend against,
what we explicitly do not, and how the trust model is laid out
across the actors in the system.

**What PHANTOM defends against.** A passive network observer
(ISP, carrier middlebox, transit-level adversary) sees only
TLS-encrypted bytes — message content, sender, recipient, and
contact graph remain confidential because every envelope is
end-to-end encrypted with the Double Ratchet and wrapped in a
Sealed Sender header before it reaches the relay. An active
network adversary that blocks endpoints (Russia's TSPU is the
worked example — see ADR-019) is defeated by the censorship-
resistance transport layer: today either Tor with operator-
controlled WebTunnel bridges (ADR-016) or Xray VLESS+REALITY
masquerading as a TLS handshake to `www.microsoft.com` (ADR-019),
production-validated 2026-05-07 on a Russian carrier. A
malicious or seized relay sees only opaque ciphertext blobs —
no plaintext, no decryptable messages; the relay's trust
posture is recorded in ADR-004 and enforced at the protocol
level. A compromise of a single device's identity key cannot
retroactively read past messages — forward secrecy is provided
by the per-message ratchet. Finally, the device-thief case:
local data is encrypted at rest via SQLCipher with a key derived
from the user's unlock authentication, so a powered-off seized
device leaks nothing useful without the user's biometric or PIN.

**What PHANTOM does not defend against.** A compromised endpoint
with the screen unlocked and the user logged in (a malicious
process with `READ_USER_DATA` permission, or root-level
adversary on the device, or a coercion scenario where the user
is forced to unlock and read messages) reveals the cleartext
view that the user themselves sees. A sophisticated state-level
adversary with hardware access (forensic memory imaging while
the app is running, side-channel attacks against the SoC's
TrustZone) is out of scope for the Alpha-stage threat model
and pushed to GrapheneOS-class hardening as user-side
operational guidance. Traffic-analysis adversaries who can
correlate metadata at the network egress *and* the relay
ingress simultaneously can probabilistically link sender to
recipient — this is the same global-passive-adversary case Tor
itself does not solve, and we accept it as out-of-scope at the
PHANTOM layer.

**Adversary capability matrix** (formalised in §3 of the Russian
body below): six adversary classes are tracked — network observer,
malicious relay, compromised backend component, malicious user
(spam / scam / flood), device thief / local compromise, fake
contact / impersonator. For each, the body documents what the
adversary can do and what they explicitly cannot. Priorities
are ranked P0/P1/P2 in §4.

**Trust model.** The user trusts only their own device's
crypto state. The relay is treated as untrusted-but-honest-by-
default and protected against by encryption rather than
authentication. The sender and recipient devices are mutually
trusted at the protocol layer (Double Ratchet contract) but
each manages its own identity-key secrecy. Verification of
contacts is user-driven — currently QR-code at first contact;
post-Alpha-2 a username directory adds a second verification
channel (ADR-005 + the "Username uniqueness" pending ADR
draft).

**Out-of-scope today, in-scope later.** Group-chat hardening
findings (Track B Security Sprint), iOS port (ADR-022 planned),
adaptive transport selection (ADR-020), and multi-server
fan-out for the Stage 5E Xray endpoint (ADR-021) are tracked
in the project's backlog with target windows in
`docs/project/MASTER_TIMELINE_2026.md` and the SECURITY_ROADMAP.

For the formal threat model with full adversary specifications,
priority matrices, and engineering obligations before each
release stage, read the Russian body below. A full English
translation is planned alongside the Beta milestone, when an
external audit pass becomes appropriate.

---

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
