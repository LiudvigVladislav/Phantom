# Privacy Policy

**Effective date:** April 27, 2026
**Last updated:** April 27, 2026

This Privacy Policy explains how PHANTOM ("the Service"), operated by Willen LLC, handles information when you use our messenger.

The short version: **we are designed to know as little about you as possible**. The rest of this document explains exactly what that means.

## What we do not collect

PHANTOM does not collect, store, or have access to:

- Your **message content** — all messages are end-to-end encrypted; only sender and recipient can read them
- **Phone numbers, email addresses, or real names** — none are required to use the Service
- **Contact lists or address books** from your device
- **Read receipts, typing indicators, or presence information** — these stay between you and your contact, not on our servers
- **Device identifiers** (IMEI, Android ID, advertising ID)
- **Location data**
- **Behavioral analytics, marketing tracking, or third-party trackers**
- **Crash reports** — the application does not transmit crash data to us
- **Profile information** (display name, date of birth, etc.) entered in the app — this is stored on your device and shared only with contacts you choose, end-to-end encrypted

## What we technically need

To deliver encrypted messages between users, our relay servers temporarily process:

| Data | Purpose | Retention |
|------|---------|-----------|
| **Public key** | To identify you on the network so others can send you encrypted messages | As long as your account is active |
| **Encrypted message envelope** | To deliver to the recipient | Maximum 7 days, then permanently deleted |
| **IP address (during connection)** | TCP/TLS connection routing | Not logged, not stored |
| **Recipient public key** | To route the envelope | Not retained after delivery |

We do not maintain access logs that link IP addresses to public keys.

## We cannot disclose what we do not have

If we receive a request from law enforcement, government, or any third party for user data, we will respond truthfully: the requested data does not exist in our systems. We cannot provide:

- Message content (we cannot decrypt it)
- Message history (we do not retain delivered messages)
- Conversation partners (we do not log who you communicate with)
- Connection history (we do not log IPs against accounts)

We may receive valid legal requests; we will respond to them in accordance with applicable law, but our technical architecture limits what we are able to provide to essentially nothing beyond what is publicly known (your public key, if specifically asked).

## Your rights under GDPR and similar laws

If you are in the European Union, United Kingdom, California, or another jurisdiction with privacy laws, you have rights regarding your personal data, including the right to access, correct, delete, or port your data.

Because we minimize data collection by design, exercising these rights is largely automatic:

- **Right to access:** We can confirm your public key (if you provide it). We hold no other data about you.
- **Right to deletion:** Delete the application from your device. We retain no profile data to erase. Encrypted envelopes awaiting delivery expire automatically within 7 days.
- **Right to portability:** Your account *is* your cryptographic keys, stored on your device. They are inherently portable.
- **Right to object:** Stop using the Service.

To make any privacy request, contact privacy@phntm.pro.

## Children's privacy

PHANTOM is not directed at children under 16, and we do not knowingly collect data from anyone under 16.

## Security

We use industry-standard cryptography:

- **Double Ratchet protocol** for forward secrecy
- **X3DH key agreement** for initial session setup
- **libsodium** primitives (XChaCha20-Poly1305, Curve25519, Blake2b)
- **TLS 1.3** for transport security between client and relay

Our relay servers run in containers on hardened Linux infrastructure, with TLS-only access through Caddy reverse proxy.

## International data transfers

Our relay infrastructure is currently hosted in the European Union (Helsinki, Finland). When you use PHANTOM, your encrypted message envelopes briefly transit through these servers regardless of your location.

## Changes to this Policy

We may update this Privacy Policy. Material changes will be communicated through the application before they take effect.

## Contact

- **Privacy questions or requests:** privacy@phntm.pro
- **Legal matters:** legal@phntm.pro
- **Security disclosure:** security@phntm.pro

Willen LLC, Wyoming, United States.
