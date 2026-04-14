# PHANTOM Claude + VS Code Pack

Этот пакет нужен, чтобы ты мог:

1. Запустить PHANTOM в Claude Code через VS Code.
2. Разделить работу на инженерный и визуальный треки.
3. Сразу использовать project-level subagents, slash-команды и базовые настройки.

## Как использовать

1. Скопируй содержимое этого пакета в корень репозитория PHANTOM.
2. Убедись, что Claude Code запускается из **встроенного терминала VS Code**.
3. Выполни `claude`.
4. Открой `/agents` и проверь, что project agents видны.
5. Открой `/help` и проверь, что project commands видны.
6. В начале сессии дай Claude задачу через одну из команд, например:
   - `/alpha0`
   - `/ui-pass`
   - `/security-pass`
   - `/slice-chat`

## Как работать параллельно

### Вариант A — самый практичный
- Основной репозиторий: архитектура, core, relay, transport.
- Отдельная ветка `ui-lab`: визуальный прототип, экраны, анимации, design tokens.

### Вариант B — два соседних приложения внутри monorepo
- `apps/mobile`: реальный продукт.
- `apps/prototype`: быстрый визуальный прототип для просмотра экранов и flow.

## Что важно

Не пытайся сразу собрать:
- discovery по телефону,
- DHT production-grade,
- mesh,
- public channels,
- payments.

Сначала собирается **Alpha-0 slice**:
- invite link / QR,
- 1:1 E2EE text,
- relay fallback,
- local encrypted history.

## Лучший режим работы

1. Один агент делает core.
2. Второй агент делает UI prototype.
3. Третий делает security review.
4. Ты смотришь diff прямо в VS Code и принимаешь/правишь решения.
