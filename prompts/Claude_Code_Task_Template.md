# Claude Code Task Template

Скопируй этот шаблон и заполняй под каждую задачу.

## Title

[Short task title]

## Objective

Что должно быть сделано и зачем это нужно для PHANTOM.

## Context

Связанные документы:
- [link to doctrine]
- [link to ADR]
- [link to threat model]
- [link to milestone]

## In scope

- ...
- ...
- ...

## Out of scope

- ...
- ...
- ...

## Constraints

- Do not introduce new product scope.
- Do not bypass existing interfaces.
- Do not store plaintext outside client runtime memory.
- Do not call crypto library directly outside approved adapter boundary.

## Deliverables

- code files changed
- tests added
- docs/spec notes updated if needed

## Acceptance criteria

- [ ] builds successfully
- [ ] tests pass
- [ ] respects ADR constraints
- [ ] no security regression introduced

## Security notes

Опиши возможные risks и что нужно проверить дополнительно.

## Review checklist

- [ ] interfaces are minimal and explicit
- [ ] no hidden global state
- [ ] no UI/business logic coupling
- [ ] failure paths handled
- [ ] restart / persistence behavior considered
