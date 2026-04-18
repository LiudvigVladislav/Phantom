# Public Spaces Moderation Model

**Project:** PHANTOM Messenger  
**Version:** 0.1-draft  
**Status:** Product and policy design draft  
**Owner:** [fill in]  
**Last updated:** 2026-04-15

## Purpose

This document defines how PHANTOM moderates **public and semi-public product surfaces** without undermining the private encrypted messaging model.

PHANTOM should treat public surfaces as a separate governance layer.
This is where the product can and should be stricter.

## What counts as a public or semi-public surface

- searchable usernames
- public profiles
- public channels
- large groups
- shareable invite links
- join-by-link communities
- discoverable communities
- recommendations
- search results
- media galleries visible beyond direct contacts
- future bots, mini-apps, and public automation surfaces

## Governance goals

1. Prevent PHANTOM from becoming a distribution hub for illegal or abusive communities.
2. Reduce abuse velocity on public surfaces.
3. Make high-reach entities more accountable than ordinary private users.
4. Maintain a review path for illegal or harmful content reports.
5. Preserve privacy in private chats while enforcing safety in public spaces.

## Moderation stance by surface

### A. Usernames and profiles
Controls:
- reportable
- blockable
- anti-impersonation review
- limited rename frequency
- reserved namespace policy for sensitive names
- verified badges only through controlled process
- ability to suppress from search

### B. Public channels
Controls:
- owner + moderator roles
- report channel
- report post
- join restrictions
- posting restrictions
- forwarding restrictions
- public contact or appeal path
- strike system
- emergency freeze
- quarantine or removal

### C. Large groups
Controls:
- owner + moderator roles
- anti-spam defaults
- restricted posting for new members
- link and media friction
- report message / report group
- public abuse review path
- invite link invalidation
- rate-limited member acquisition
- restricted discoverability if risk score rises

### D. Discovery and recommendations
Controls:
- never recommend accounts or groups under active safety review
- never recommend sexual or extremist content
- suppress suspicious entities from search/discovery
- prioritize safe/trusted entities
- recommendation systems must obey policy, not just engagement metrics

## Default rule: public reach is earned, not automatic

Public surfaces should use a trust-sensitive model.

Recommended unlock path:
- private use first
- low public reach by default
- expanded public capabilities only after trust signals
- verification or manual approval for high-reach features if needed

Possible unlocks:
- create public channel
- appear in discovery
- generate public invite links
- create large groups
- post at high volume
- use public automation features

## Trust model for public spaces

### Tier 0 — New / untrusted
- no public channel creation
- no broad search surfacing
- strict invite limits
- high rate limits
- all abuse reports prioritized

### Tier 1 — Normal user
- limited group participation
- small-scale public actions
- normal report processing

### Tier 2 — Established account
- expanded community tools
- better default reach
- lower friction, but still reviewable

### Tier 3 — Verified public entity
- public identity checks
- elevated tools
- faster support path
- higher expectations and stronger consequences for abuse

## Moderation triggers

Public-space moderation should trigger on:
- repeated user reports
- trusted notices / trusted flaggers where applicable
- public-content review findings
- rapid member growth with suspicious signals
- repeated link abuse
- repeated impersonation claims
- violent extremist markers on public surfaces
- child-safety risk indicators
- malware / phishing indicators
- evasion after prior enforcement

## Enforcement ladder

1. Soft friction
   - posting delay
   - link cooldown
   - reduced reach
   - temporary discovery suppression

2. Restriction
   - invite suspension
   - posting restriction
   - media restriction
   - moderator-only mode

3. Freeze
   - channel/group frozen
   - join disabled
   - search visibility removed
   - content review queue enabled

4. Removal
   - entity removed
   - invite invalidated
   - owner account reviewed
   - associated abusive infrastructure investigated

## Special handling: terrorism and extremist propaganda

Public surfaces must have a dedicated path for:
- propaganda
- recruitment
- operational instruction
- glorification of terrorist acts
- distribution of manifestos or instructional material
- mirrored re-uploads after removal

Controls:
- priority review
- emergency freeze or removal
- evidence preservation
- legal-notice handling
- repeat uploader enforcement
- search suppression and invite invalidation

## Special handling: child safety on public surfaces

The following should result in immediate containment-oriented response:
- sexualized depictions of minors
- groups or channels centered on exploitative interest in minors
- grooming-oriented public outreach
- child trafficking or solicitation signals
- CSAM distribution or linking

Controls:
- immediate freeze or removal
- account review
- evidence preservation
- escalation under child-safety runbook

## User-facing tooling

Public-space safety UX should include:
- report post
- report profile
- report channel/group
- report invite link
- mute
- block owner or admins
- leave and hide
- reason-specific reporting categories
- visible channel/group rules
- visible moderation actions where appropriate

## Moderator tooling

Internal tools should support:
- queue by severity
- queue by legal urgency
- queue by child-safety urgency
- history of prior strikes
- cross-linked entity view
- search suppression
- invite invalidation
- channel freeze
- account restriction
- case notes
- appeal status

## Appeals and due process

Non-emergency enforcement should support:
- internal case ID
- notification where lawful and appropriate
- appeal path
- documented outcome
- proportionality review

Emergency cases involving terrorism, child safety, or imminent harm may justify immediate action before appeal.

## Metrics PHANTOM should track

Not public engagement metrics only.
Track safety metrics too:
- report volume by category
- median review time
- repeat offender rate
- successful appeal rate
- spam-first-contact rate
- fraud complaint rate
- public invite abuse rate
- extremist / CSAM urgent case response time
- number of frozen/removed public entities

## Android prototype requirements now

In the Android prototype, public spaces should already be modeled differently from private chats.

Recommended UI states and components:
- public entity badge / state chip
- visible rules panel
- report entry point on profile, post, channel, group
- admin tools placeholder
- join friction states
- moderation state chips: Active / Limited / Frozen / Removed
- trust tier badge in admin views
- search suppression and appeal placeholders in admin or debug flows

Suggested domain model fields:
- `visibility: Private | Unlisted | Public`
- `trustTier: T0 | T1 | T2 | T3`
- `moderationState: Active | Limited | Frozen | Removed`
- `reportCount`
- `isSearchSuppressed`
- `publicInviteState: Enabled | Disabled | Revoked`

## Launch gate for public spaces

Do not launch public channels or large public groups until:
- [ ] reporting exists
- [ ] moderation queue exists
- [ ] strike model exists
- [ ] emergency freeze exists
- [ ] search suppression exists
- [ ] child-safety runbook exists
- [ ] terrorism-content runbook exists
- [ ] legal notice handling exists
- [ ] founder accepts moderation scope and staffing burden
