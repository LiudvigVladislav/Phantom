# Параллельный визуальный трек

## Цель
Не ждать, пока будет готов networking/crypto, чтобы уже видеть продукт глазами.

## Предлагаемая структура

```text
apps/
  mobile/
    android/
    ios/
  prototype/
    web/
    assets/
```

## Что должно быть в prototype

1. Splash / onboarding
2. Username creation
3. Chat list
4. 1:1 chat
5. Profile / safety screen
6. Nearby / offline mode concept screen
7. Settings / transport status

## Зачем это нужно

- ты раньше видишь product feel;
- проще принимать решения по UX;
- можно корректировать brand direction до deep implementation;
- удобно давать Claude конкретные задачи по экрану.

## Workflow

1. Claude делает экран в prototype.
2. Ты смотришь preview.
3. Утверждаешь визуал.
4. Потом только переносишь в реальный KMP UI.

## Правило

Прототип не должен тянуть real crypto/network dependencies.
Он должен быть быстрым, лёгким и disposable.
