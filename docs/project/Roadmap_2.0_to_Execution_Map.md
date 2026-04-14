# Roadmap 2.0 → Execution Map

Этот файл связывает текущий roadmap PHANTOM с практической последовательностью инженерного старта.

## Что уже хорошо определено в roadmap

Roadmap уже задает:
- позиционирование продукта;
- стек клиента: KMP + Compose;
- криптографическую базу: Signal Protocol / Double Ratchet / X3DH;
- encrypted local storage;
- transport portfolio;
- relay / bootstrap / discovery roles;
- phased delivery от ядра к расширению.

## Что нужно добавить до активной разработки

Roadmap — это стратегическая карта.
Перед кодом нужны операционные документы:

1. Product Doctrine
2. Threat Model
3. ADR set
4. Monorepo structure
5. Alpha-0 milestone
6. Task templates for agents

## Mapping phases

### Roadmap phase: Preparation
Практически означает:
- freeze doctrine;
- freeze ADRs;
- create repo skeleton;
- setup CI;
- setup local test harness.

### Roadmap phase: Crypto core
Практически означает:
- identity model;
- session orchestration;
- crypto adapter boundary;
- secure storage;
- local encrypted DB.

### Roadmap phase: Discovery + P2P
Практически на старте должно быть сужено до:
- invite links;
- QR add flow;
- basic username lookup.

Сложный discovery лучше отложить.

### Roadmap phase: Apps
Практически означает:
- onboarding;
- chat list;
- chat screen;
- message state rendering;
- restart persistence;
- settings minimal.

### Roadmap phase: Obfuscation / Offline / Mesh
Не должны блокировать Alpha-0.
Их правильно начинать после появления стабильного secure messaging core.

## Recommended order of execution

1. Docs freeze
2. Repo + tooling
3. Identity + storage + crypto boundaries
4. Fake transport + relay service
5. First 1:1 text vertical slice
6. Username lookup
7. Hardening / retry / policy basics
8. Only then advanced transports and offline modes
