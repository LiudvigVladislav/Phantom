# ADR-003: Transport Abstraction

Статус: proposed  
Дата: 2026-04-13

## Context

PHANTOM должен поддерживать разные каналы доставки: relay, direct, later obfuscated transports,
Bluetooth, Wi-Fi Direct и другие. Если transport logic будет смешана с message logic,
система быстро станет неуправляемой.

## Decision

Вводится transport abstraction layer.
Все message pipelines работают только через общий набор интерфейсов.

## Core interfaces

### `Transport`
Описывает конкретную транспортную реализацию.

Пример обязанностей:
- initialize();
- send(encryptedEnvelope);
- fetch();
- acknowledge();
- probeHealth();
- capabilityReport().

### `TransportCapabilities`
Определяет свойства транспорта:
- online / offline tolerant;
- duplex / store-and-forward;
- size limits;
- latency class;
- attachment support;
- background friendliness.

### `TransportHealth`
Определяет текущее состояние:
- available;
- degraded;
- blocked;
- unreachable;
- backoffUntil.

### `TransportSelector`
Отвечает за:
- выбор preferred transport;
- fallback при деградации;
- health-based ordering;
- policy-aware disable/enable.

### `TransportEnvelope`
Transport получает только зашифрованный envelope и routing metadata,
минимально необходимую для доставки.

## Initial transport set for early phases

### Alpha-0
- RelayTransport
- Loopback/FakeTransport for tests

### Alpha-1 / later
- DirectTransport
- WebSocketObfuscatedTransport
- TorBridgeTransport

### Later phases
- BluetoothTransport
- WifiDirectTransport
- MeshTransport

## Transport selection principles

1. Messaging layer не должна знать, через какой transport ушло сообщение.
2. Переключение transport не должно менять crypto guarantees.
3. Health scoring должен быть data-driven и testable.
4. Каждая transport implementation должна иметь explicit limits.
5. Background-delivery constraints учитываются как capability, а не хаки в UI.

## Why not build direct-first?

Потому что relay-first путь быстрее даёт working vertical slice:
- проще тестировать;
- проще reason about delivery semantics;
- не требует раннего NAT traversal complexity;
- позволяет позже встроить direct transport без слома messaging core.

## Consequences

### Positive
- модульность;
- проще расширять transport set;
- проще симулировать блокировки и деградацию.

### Negative
- чуть выше upfront abstraction cost.

## Rejected alternative

**Alternative:** жестко зашить relay/send logic в message service.  
**Rejected because:** это усложнит переход к direct/BLE/Wi-Fi transports и создаст сильную связность.
