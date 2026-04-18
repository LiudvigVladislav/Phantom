# Abuse Prevention Doctrine

**Project:** PHANTOM Messenger  
**Version:** 0.1-draft  
**Status:** Working product doctrine for prototype and MVP  
**Owner:** [fill in]  
**Last updated:** 2026-04-15

## Purpose

This document defines how PHANTOM reduces abuse risk **without breaking the privacy model** of the product.

PHANTOM is not designed as an anonymous free-for-all. It is designed as a **privacy-first messenger with accountable public surfaces**. The system must make private communication safe and resilient, while making mass abuse, recruitment, coordinated fraud, and distribution of illegal content materially harder.

This doctrine is intentionally stricter for **public or discovery-facing surfaces** than for **private 1:1 encrypted conversations**.

## Core principles

1. **Private chats are not a moderation playground**
   - PHANTOM should not rely on reading private end-to-end encrypted chats to enforce safety.
   - The system should prefer metadata-minimizing, behavior-based, and surface-based controls.

2. **Public surfaces are accountable by design**
   - Public groups, channels, discovery, usernames, invite links, recommendations, and searchable profiles must have safety controls, moderation flows, and enforcement hooks.

3. **Abuse prevention starts with reach control**
   - The most effective early defense is limiting reach, virality, and cold-contact spam for new or low-trust accounts.

4. **Safety tooling must exist from day one**
   - Report, block, mute, hide, appeal, escalation, enforcement logging, and emergency response are MVP-level requirements.

5. **Minimal data, maximum operational clarity**
   - PHANTOM should collect only the minimum service data necessary for security, abuse handling, and legal compliance.
   - Abuse-response workflows must be clear, auditable, and role-based.

6. **No marketing claims that sabotage safety**
   - Do not market PHANTOM as "fully anonymous for anything", "impossible to trace", or "unmoderated".
   - Positioning should be: **private by default, resilient by design, accountable in public spaces**.

## Scope split

### Private Plane
Applies to:
- 1:1 encrypted chats
- small private groups
- direct contact via invite or explicit approval
- local history and encrypted message flows

Target model:
- minimal visibility
- minimal metadata
- no routine content inspection
- abuse handled primarily through user control and edge enforcement

Allowed controls:
- block user
- mute user
- message requests
- first-contact restrictions
- rate limits
- delivery throttling
- abuse reporting from recipient side
- account-level enforcement based on observed abuse patterns or verified reports

### Public Plane
Applies to:
- discoverable usernames
- public channels
- large groups
- public profile cards
- public join links
- searchable posts or media
- future recommendation/discovery surfaces

Target model:
- active moderation
- reportability
- rapid enforcement
- explicit safety policy
- human review path
- stronger identity and trust controls for high-reach entities

## Threat classes PHANTOM must explicitly defend against

1. Terrorist propaganda and recruitment
2. CSAM / child sexual abuse and exploitation
3. Child grooming and predatory outreach
4. Fraud rings and impersonation
5. Malware / phishing / credential theft
6. Harassment, threats, doxxing
7. Spam and mass unsolicited contact
8. Coordinated harmful communities on public surfaces
9. Evasion after enforcement
10. Use of relay/discovery infrastructure for abusive automation

## Product-level safety controls

### A. Reach controls for new accounts

New accounts must not immediately receive full network power.

Default restrictions for low-trust accounts:
- limited number of new outbound chats per day
- limited number of public joins or invites in a short period
- limited ability to mass-forward
- no bulk contact import into outbound outreach
- no discoverability boost
- no public channel creation at account birth
- no public invite link creation at account birth

Unlock strategy:
- account age
- successful non-abusive usage
- trust signals
- optional verification tiers for public-facing or business use
- clean abuse history

### B. Message request model

For non-contacts and cold outreach:
- messages should land in **Message Requests**
- media previews may be blurred or hidden by default
- links may be neutralized or warning-tagged until accepted
- sender should not gain immediate delivery certainty until accepted

### C. Rate limiting and friction

Apply at least to:
- account creation velocity
- username changes
- invite generation
- public posting
- join attempts
- forwarding bursts
- media upload bursts
- repeated first contacts
- repeated delivery failures across many recipients

Friction can include:
- cooldowns
- captcha-like proof of work for suspicious patterns
- staged unlocks
- temporary holds on risky public actions

### D. Trust tiers

Recommended trust model:
- **Tier 0:** fresh / untrusted
- **Tier 1:** normal private user
- **Tier 2:** established account with clean history
- **Tier 3:** verified public entity / verified business / trusted org

### E. Reporting and blocking

Must exist in both prototype planning and MVP:
- block user
- report user
- report message
- report public group/channel
- report profile / username / invite link
- appeal flow for enforcement
- internal case logging

Reporting should support:
- terrorism / violent extremism
- child safety
- grooming
- sexual exploitation
- fraud / scam
- phishing / malware
- impersonation
- threats / violence
- harassment / doxxing
- illegal goods / criminal coordination
- spam

### F. Public space safeguards

For public groups/channels:
- owner and moderator roles
- join controls
- post permissions
- default anti-spam settings
- content/report review queue
- forwarding limits
- link reputation handling
- public admin contact
- repeat-abuse strike model
- emergency freeze or quarantine mode

### G. Anti-impersonation controls

Recommended:
- reserved usernames for brands / emergency services / known entities
- verified badge only after manual or trusted verification
- visible account age for public entities
- risk flags on suspicious lookalike names
- limits on profile/photo/username changes in quick succession

### H. Safety-preserving evidence handling

PHANTOM should avoid broad surveillance, but must preserve enough structured evidence to handle abuse:
- report category
- reported object type
- timestamp
- actor identifiers used internally
- prior enforcement history
- limited service-side logs necessary for security and legal handling
- secure chain-of-custody for emergency cases

## Enforcement model

### Account actions
- warning
- temporary feature restriction
- message-request-only mode
- public-surface suspension
- invite generation suspension
- channel/group freeze
- account suspension
- permanent ban

### Entity actions
- message removal (public surfaces only)
- group freeze
- channel removal
- invite invalidation
- username hold / reclaim
- search suppression
- recommendation suppression
- media quarantine

### Infrastructure actions
- relay throttling
- relay denylisting for abusive automation patterns
- IP / device risk scoring where lawful and proportionate
- abuse-triggered proof-of-work requirements
- high-risk public operation gating

## Non-goals

PHANTOM will not claim:
- perfect prevention of all illegal use
- blanket immunity from abuse
- total untraceability for all circumstances
- a zero-governance model

Instead, PHANTOM aims for:
- strong private communication protections
- clear abuse policy
- strong public-surface governance
- fast response to severe illegal use
- minimal but sufficient operational controls

## Roles and responsibilities

### Safety Lead
Owns policy, severe case review, escalation standards.

### Moderation / Trust & Safety
Handles user reports, public-surface enforcement, appeals.

### Security Lead
Owns abuse detection thresholds, relay/discovery hardening, incident response.

### Legal / Compliance Contact
Receives formal legal notices, removal orders, emergency notices.

### Engineering
Implements policy hooks, logging, controls, case tooling.

## MVP requirements derived from this doctrine

Before public launch, PHANTOM should have:
- published Terms and Community Rules
- in-app report and block flows
- message requests for non-contacts
- account reach limits for new users
- public entity moderation hooks
- enforcement logging
- designated safety / legal contact
- emergency response runbook
- app-store-safe policy language

## Prototype tasks for Android right now

Even in a prototype, include placeholders for:
- block action in chat/profile screen
- report action in chat/profile/public surface
- message requests inbox
- safety categories list
- trust state enum on account model
- moderation hooks in public features
- public/private surface separation in navigation and data model

## Open decisions for founder

1. Will public channels exist in MVP or only later?
2. Will non-contacts be able to send text immediately, or only requests?
3. Which trust signals are acceptable for public verification?
4. What minimum service logs are retained, for how long, and where?
5. Which jurisdiction and legal contact model will PHANTOM use at launch?

## Approval checklist

- [ ] Reviewed by founder
- [ ] Reviewed by security lead
- [ ] Reviewed by legal counsel
- [ ] Reflected in product requirements
- [ ] Reflected in Android prototype
- [ ] Reflected in future iOS parity plan
