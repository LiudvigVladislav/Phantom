# Android Prototype Integration Checklist

**Goal:** reflect safety architecture in the Android prototype now, not after launch.

## Add to navigation / IA
- [ ] Private chats and public spaces are modeled separately
- [ ] Message Requests screen exists or is planned
- [ ] Public channel/group screens have report entry points
- [ ] Profile screen has Block + Report actions

## Add to domain models
- [ ] `TrustTier`
- [ ] `ModerationState`
- [ ] `Visibility`
- [ ] `SafetyReportCategory`
- [ ] `MessageDeliveryState`
- [ ] `ContactState` or request/accepted states

## Add to UI placeholders
- [ ] Report dialog bottom sheet
- [ ] Safety category selection
- [ ] Block confirmation dialog
- [ ] Public rules panel
- [ ] Moderation state chips
- [ ] Channel freeze / limited state placeholder
- [ ] Search-suppressed state placeholder

## Add to product flows
- [ ] Non-contact message goes to Message Requests
- [ ] New account cannot instantly create public channel
- [ ] Public invite link can be disabled or revoked
- [ ] Public post/message can be reported
- [ ] Public entity can be hidden from discovery

## Add to backlog
- [ ] Trust & Safety queue backend placeholder
- [ ] Abuse case logging placeholder
- [ ] Legal notice intake placeholder
- [ ] Child safety point-of-contact placeholder
- [ ] Emergency freeze action for public entities

## Suggested immediate UI work in Android Studio
1. Profile screen: add Block / Report actions.
2. Chat screen: add Report message action.
3. Add Message Requests screen stub.
4. Public group/channel card: add moderation chips.
5. Settings / About: add Safety and Community Rules placeholder.
