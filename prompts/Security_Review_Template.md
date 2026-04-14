# Security Review Template

Используй этот шаблон после реализации задачи, особенно если она касается identity / crypto / transport / relay.

## Scope reviewed

Какие модули и файлы проверялись.

## Questions

1. Может ли backend/relay увидеть plaintext?
2. Возникла ли новая metadata leakage surface?
3. Есть ли скрытая зависимость между UI и crypto/transport?
4. Есть ли invalid transitions, которые не обработаны?
5. Можно ли злоупотребить новым API для spam / abuse?
6. Можно ли вызвать race condition или duplicate delivery?
7. Есть ли логирование чувствительных данных?
8. Можно ли обойти policy / trust restrictions?

## Findings

### Critical
- ...

### High
- ...

### Medium
- ...

### Low
- ...

## Recommended fixes

- ...
- ...
- ...

## Final verdict

- Approved
- Approved with fixes
- Blocked until issues resolved
