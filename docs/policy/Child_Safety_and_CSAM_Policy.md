# Child Safety and CSAM Policy

**Project:** PHANTOM Messenger  
**Version:** 0.1-draft  
**Status:** Mandatory policy for prototype-to-launch path  
**Owner:** [fill in]  
**Last updated:** 2026-04-15

## Important note

This document is a product and operational policy draft. It is not legal advice.
Before public launch, it must be reviewed by counsel in the jurisdictions where PHANTOM is distributed or offered.

## Zero-tolerance statement

PHANTOM prohibits:
- child sexual abuse material (CSAM)
- child sexual exploitation and abuse (CSEA / CSAE)
- child grooming
- sextortion of minors
- trafficking or solicitation involving minors
- sexualized depictions of minors
- communities, accounts, or infrastructure used to facilitate any of the above

Any confirmed or strongly substantiated use of PHANTOM for these purposes is grounds for immediate enforcement and emergency escalation.

## Scope

This policy applies to:
- user accounts
- public groups and channels
- usernames, bios, and profile media
- invite links
- file and media sharing
- future discovery and recommendation systems
- support channels and abuse reports
- relay, directory, and service infrastructure where relevant

## Product doctrine for child safety

1. **No tolerance**
   - PHANTOM does not permit child sexual abuse or exploitation in any product surface.

2. **Safety without broad surveillance**
   - PHANTOM should not depend on general inspection of private encrypted chats as its default model.
   - PHANTOM must still maintain an emergency workflow for actionable abuse knowledge, public-surface moderation, and legally required reporting.

3. **Public surfaces get stronger controls**
   - Public channels, large groups, discoverable profiles, and searchable media require stronger review and enforcement hooks than private 1:1 communications.

4. **Actual knowledge triggers action**
   - Once PHANTOM obtains actual knowledge through reports, public moderation review, trusted notices, or formal legal process, action must be prompt and documented.

5. **Safety beats feature growth**
   - If a product surface creates disproportionate child-safety risk, that surface should be restricted, delayed, or removed.

## Prohibited conduct

The following are prohibited and non-negotiable:
- uploading, storing, transmitting, requesting, distributing, advertising, or linking to CSAM
- encouraging or coordinating sexual exploitation of minors
- grooming behavior directed at minors
- sexual extortion involving minors
- trading, cataloging, or indexing exploitative material
- using PHANTOM to recruit minors for offline exploitation
- using PHANTOM to evade prior child-safety enforcement
- creating groups or channels centered on minors in sexualized contexts
- sexualized roleplay or simulated exploitation involving minors
- accounts impersonating minors for predatory purposes

## Safety controls

### A. Reporting

PHANTOM must provide:
- report user
- report message
- report media
- report group/channel
- report username/profile
- emergency report path for imminent child danger

Report categories must explicitly include:
- child sexual abuse material
- child grooming
- sextortion
- child trafficking / solicitation
- sexual exploitation of a minor

### B. Blocking and contact controls

PHANTOM must include:
- user blocking
- message requests for non-contacts
- limits on cold outreach
- hidden media previews in message requests where appropriate
- restrictions on mass first-contact behavior

### C. Public-surface restrictions

Recommended defaults:
- no public recommendation of sexual content
- strong moderation on public channels and large groups
- restricted discoverability for new accounts
- public channel creation limited to accounts above a trust threshold
- suspicious public entities reviewable and removable

### D. Child-safety point of contact

PHANTOM must designate a named operational role and monitored mailbox for child-safety issues:
- receives notices
- coordinates internal review
- escalates urgent cases
- coordinates lawful reporting where required
- maintains case records

Placeholder:
- **Child Safety Contact:** [fill in email / role]

## Intake and triage

### P0 — Immediate danger
Examples:
- credible imminent threat to a child
- live abuse coordination
- extortion with immediate risk
- active trafficking situation

Action target:
- immediate triage
- fast internal escalation
- preserve necessary evidence
- follow emergency legal/reporting obligations

### P1 — Confirmed or strongly supported CSAM / grooming
Examples:
- reported exploitative media
- explicit grooming conversation reports
- group/channel facilitating abuse

Action target:
- rapid review
- containment and enforcement
- evidence preservation
- legal escalation as required

### P2 — Suspicious but unconfirmed
Examples:
- suspicious accounts targeting minors
- repeated predatory contact attempts
- link-sharing patterns associated with exploitation

Action target:
- restrict reach
- enhanced monitoring on public surfaces
- further review
- prevention-first enforcement where justified

## Enforcement actions

Depending on certainty and severity:
- remove public content
- freeze group/channel
- revoke invite links
- quarantine media
- suspend public posting
- suspend account
- permanently ban account
- preserve evidence package
- escalate to competent authorities / designated reporting body where required

For severe child-safety cases, PHANTOM should prefer **containment first, then appeal later**.

## Evidence handling

When PHANTOM receives a child-safety report or formal notice:
- create case ID
- preserve timestamp, reporter context, reported object ID, relevant service-side metadata lawfully available
- preserve moderation actions taken
- restrict internal access to need-to-know staff only
- document chain of custody for preserved evidence
- do not overshare internally

Retention windows and storage locations must be defined by legal and security review before launch.

## Public surfaces and minors

PHANTOM should avoid product patterns that create unnecessary child-safety risk:
- no default random chat
- no anonymous match-making with minors
- no recommendation engine that pushes sexual content
- no public surfacing of minors as aesthetic objects
- no age-ambiguous NSFW spaces
- no growth loop built around risky cold contact

If the app ever permits age-sensitive communities:
- access controls
- declared age gates where appropriate
- stronger moderation
- no sexualized positioning of minors under any circumstances

## Staff handling standard

Anyone handling child-safety cases must have:
- documented procedure
- limited access
- escalation map
- mental health safeguards where possible
- no casual sharing of case materials

## Required public policy language

Before release, PHANTOM should publish:
- Terms / Community Rules prohibiting CSAE
- child safety standards page
- reporting instructions
- point of contact
- enforcement notice language
- appeal language for non-emergency cases

## Android prototype requirements now

Even at prototype stage:
- include "Report" entry points in chat/profile/public screens
- include child-safety categories in report flow
- include "Block user" and "Restrict contact" actions
- model message requests for non-contacts
- separate public entities from private chats in nav and data model
- add placeholder moderation state to group/channel models

Suggested model enums:
- `SafetyReportCategory.ChildCSAM`
- `SafetyReportCategory.ChildGrooming`
- `SafetyReportCategory.ChildSextortion`
- `SafetyReportCategory.ChildTrafficking`
- `ModerationState.Active / Restricted / Frozen / Removed`
- `TrustTier.T0 / T1 / T2 / T3`

## Launch gate

PHANTOM should not publicly launch until all below are true:
- [ ] public child safety standards are published
- [ ] in-app reporting exists
- [ ] child safety point of contact exists
- [ ] emergency escalation runbook exists
- [ ] public-surface enforcement hooks exist
- [ ] legal reporting workflow exists for relevant jurisdictions
- [ ] internal case logging exists
- [ ] founder has reviewed tradeoffs with legal counsel
