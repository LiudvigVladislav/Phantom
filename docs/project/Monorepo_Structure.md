# Monorepo Structure

Ниже — рекомендуемая стартовая структура monorepo для PHANTOM.
Она сделана так, чтобы:
- shared core не смешивался с app shell;
- сервисы могли развиваться независимо;
- агентам было легко выдавать изолированные задачи;
- тестовый harness и localnet не жили в случайных папках.

## Proposed structure

```text
phantom/
  README.md
  docs/
    doctrine/
    threat-model/
    adr/
    protocols/
    product/
    project/
  apps/
    android/
    ios/
  shared/
    core/
      identity/
      crypto/
      storage/
      transport/
      messaging/
      discovery/
      policy/
    features/
      onboarding/
      contacts/
      chatlist/
      chat/
      profile/
      settings/
      nearby/
      message-requests/
    platform/
      secure-storage/
      background/
      connectivity/
      bluetooth/
      wifi-direct/
  services/
    bootstrap/
    relay/
    directory/
    connectivity/
  tooling/
    localnet/
    fixtures/
    test-harness/
    load-tests/
  prompts/
  scripts/
  .github/
```

## Folder responsibilities

### `apps/`
Тонкие shell-приложения.
Содержат:
- lifecycle wiring;
- navigation entrypoints;
- permission handling;
- platform-specific bootstrapping;
- DI composition root.

### `shared/core/`
Доменное ядро. Здесь живут:
- use-cases;
- domain models;
- state machines;
- transport selection logic;
- crypto orchestration;
- repositories interfaces.

### `shared/features/`
Feature-level presentation + orchestration.
Никакой прямой работы с low-level network/crypto здесь быть не должно.

### `shared/platform/`
Тонкие platform adapters.
Например:
- Keychain / Keystore;
- background execution;
- Bluetooth APIs;
- connectivity observers.

### `services/`
Независимые сетевые компоненты:
- relay;
- bootstrap;
- directory;
- connectivity helpers.

### `tooling/`
Все, что нужно для симуляции и проверки системы локально:
- fake nodes;
- test fixtures;
- delivery simulators;
- chaos / retry scripts.

## Repository conventions

### Naming
- ADR — в `docs/adr/ADR-XXX-*.md`
- Specs — рядом с feature или service
- Domain terms — единообразные и зафиксированные в glossary позже

### Code ownership
Рекомендуется завести code owners хотя бы логически:
- one owner for core:crypto + identity
- one owner for transport + relay
- one owner for features
- one owner for docs/ADR/security review

### Merge rule
Любой нетривиальный merge должен иметь:
- spec или task brief;
- acceptance criteria;
- тесты;
- security notes, если задача затрагивает crypto/identity/network.
