# Tor Feasibility Research — 2026-05-04

## Цель

Проверить жизнеспособность Tor onion service как primary transport
для PHANTOM relay вместо текущего WSS-over-public-Internet,
с целью обхода МТС/TSPU/CGN issues которые ломают voice delivery
на российских мобильных сетях без VPN.

## Контекст

Previous transport investigation
(`docs/research/transport-investigation-2026-05-04/`) пришло к выводу
что МТС WiFi → Hetinki dropps WS connection silently через ~120с.
Layer 2 TCP keepalive (ADR-014) дал partial improvement (50с → 120с)
но не решил проблему полностью — voice bursts всё равно не доходят.

Vladislav проверил три architectural альтернативы:
- Cloudflare Tunnel — отвергнуто (Threat Model conflict, Cloudflare
  unstable in RU, US-corporate gatekeeper)
- Russian VPS relay — отвергнуто (политически)
- **Tor onion service** — выбрано для feasibility check

## Decision criteria

Tor транспорт жизнеспособен для PHANTOM если все следующие true:
1. Performance acceptable: voice 5-30s доходит через Tor в разумное
   время (< 10 секунд для 30-секундного сообщения)
2. Mobile Tor работает на Android без unacceptable battery drain
3. Censorship resistance подтверждена в РФ 2026 (snowflake/WebTunnel
   bridges работают)
4. Identity migration path есть для existing X25519 identities
5. Coexistence с UnifiedPush не требует архитектурного перерыва
6. App Store/Google Play не блокируют embedded Tor

## Структура

| Файл | Тема | Owner |
|---|---|---|
| `01-tor-architecture.md` | Onion service hosting, WSS-over-Tor, bandwidth, server-side cost | Agent A |
| `02-mobile-tor-options.md` | Android lib options, iOS challenges, battery, APK size | Agent B |
| `03-bridges-and-censorship.md` | snowflake/WebTunnel/obfs4 status RU 2026, auto-bridge UX | Agent B |
| `04-precedents-analysis.md` | Briar, Cwtch, SecureDrop, Ricochet — lessons learned | Agent C |
| `05-client-architecture.md` | Always-on vs auto-detect vs user toggle vs hybrid | Agent C |
| `06-security-analysis.md` | Tor threat model + PHANTOM-specific (onion in QR, identity bind) | Agent C |
| `07-unified-push-integration.md` | Combined Tor + UnifiedPush architecture | Agent C |
| `08-risk-assessment.md` | R1-R6 risks из directive + mitigations | Synthesis |
| `99-recommendation.md` | GO / NO-GO / ALTERNATIVE с reasoning | Synthesis |

## Status

- 2026-05-04: Research kickoff, 3 agents in parallel
- Awaiting Vladislav approval on `99-recommendation.md` before any
  implementation work in `feat/tor-transport` branch
