# Backlog — First 20 Tasks

Ниже — стартовый backlog, который можно сразу переносить в GitHub issues.

## Foundation / docs

### 1. Freeze Product Doctrine
**Result:** утвержденный `Product_Doctrine.md`  
**Acceptance:** founder confirms invariants and out-of-scope list

### 2. Finalize Threat Model v0
**Result:** threat priorities agreed  
**Acceptance:** P0/P1 risks marked and linked to mitigations

### 3. Approve ADR set 001–005
**Result:** baseline architecture decisions frozen  
**Acceptance:** no unresolved contradictions across ADRs

## Repo / tooling

### 4. Bootstrap monorepo skeleton
**Result:** folder structure created  
**Acceptance:** apps/shared/services/tooling all present

### 5. Setup KMP shared workspace
**Result:** shared modules compile  
**Acceptance:** android + ios build against shared core skeleton

### 6. Setup CI for lint + unit tests
**Result:** CI pipeline runs  
**Acceptance:** PR checks green on sample modules

### 7. Create localnet test harness skeleton
**Result:** fake relay + fake transport harness exists  
**Acceptance:** local integration test can run end-to-end stub flow

## Core implementation

### 8. Implement identity domain model
**Result:** local identity creation flow  
**Acceptance:** identity keys + metadata persisted via interfaces

### 9. Implement secure storage adapters
**Result:** platform secure storage bridge  
**Acceptance:** Android Keystore / iOS Keychain adapter stubs working

### 10. Implement encrypted local DB skeleton
**Result:** local repositories for conversations/messages/session state  
**Acceptance:** app restart preserves stored encrypted records

### 11. Define session orchestration interfaces
**Result:** clear contracts for session establishment  
**Acceptance:** spec + interfaces merged before implementation

### 12. Integrate crypto adapter boundary
**Result:** crypto module wraps chosen audited library through narrow interfaces  
**Acceptance:** no direct crypto lib usage outside adapter boundary

### 13. Implement message domain model + state machine
**Result:** message lifecycle codified  
**Acceptance:** invalid transitions tested

## Transport / relay

### 14. Implement Transport interfaces + FakeTransport
**Result:** transport abstraction alive  
**Acceptance:** messaging pipeline can send through fake transport

### 15. Implement Relay service MVP
**Result:** ciphertext store-and-forward service  
**Acceptance:** accepts, stores, fetches, expires blobs

### 16. Implement RelayTransport client adapter
**Result:** client can use relay as transport  
**Acceptance:** integration test passes with two identities

### 17. Implement delivery state transitions
**Result:** accepted/fetched/failed states update correctly  
**Acceptance:** persisted + rendered in UI model

## UX / feature vertical slice

### 18. Implement invite link flow
**Result:** one user can invite another  
**Acceptance:** invite parsed, identity imported, session can begin

### 19. Implement 1:1 text chat vertical slice
**Result:** first real chat flow  
**Acceptance:** send -> relay -> receive -> decrypt -> render

### 20. Run Alpha-0 demo checklist
**Result:** three demo scenarios pass  
**Acceptance:** clean start, offline receiver, app restart persistence

## Suggested labels

- `foundation`
- `security`
- `core`
- `transport`
- `relay`
- `feature`
- `docs`
- `alpha-0`
