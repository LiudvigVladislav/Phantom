// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! UnifiedPush wake-up channel via self-hosted ntfy distributor.
//!
//! Architecture: ADR-016 (Tor + UnifiedPush hybrid transport).
//!
//! When the relay queues an envelope for a recipient who is not currently
//! attached over WebSocket, it fires a wake-up POST to the recipient's
//! pre-registered ntfy topic. The client's UnifiedPush distributor on
//! the recipient device wakes the app, which then opens its data channel
//! (direct WSS or Tor onion depending on Privacy Mode) and pulls queued
//! envelopes. After draining, the app goes back to passive — Tor daemon
//! and WS connection are torn down to save battery.
//!
//! Privacy boundary
//! ----------------
//! The push payload carried by this channel is exactly **one byte** (a
//! literal NUL character). It carries no metadata — not envelope id,
//! not sender, not size. The distributor therefore sees only the
//! existence and timestamp of a wake-up event for a given topic. Topic
//! values themselves are 32-byte random tokens minted client-side per
//! install, so the topic value is not a long-term identifier of the
//! user from the distributor's perspective; it is rotated at each
//! re-installation or user-initiated rotation.
//!
//! No retries, no error propagation
//! --------------------------------
//! Push is a *hint* layered on top of the durable store. The relay's
//! store-and-forward queue is the source of truth — if the push is
//! dropped, lost, or rate-limited, the envelope is still delivered the
//! next time the recipient opens any transport. We therefore do not
//! retry pushes, do not back off, and do not propagate errors out to
//! the calling delivery path. A failed push is logged at debug level
//! and the envelope-delivery operation completes normally.

use std::sync::Arc;

use crate::state::AppState;

/// Body sent on the UnifiedPush wake-up. Single NUL byte. Distributor
/// passes the payload through; the client's UnifiedPush handler ignores
/// the body and only uses the wake-up event itself as the signal.
const PUSH_PAYLOAD: &[u8] = &[0u8];

/// Best-effort UnifiedPush wake-up to an offline recipient.
///
/// `recipient_identity` is the hex public key of the offline recipient.
/// Looks up their registered topic URL in `state.push_tokens`; if
/// present, POSTs a single NUL byte to that URL.
///
/// Spawns a detached task so the caller is never blocked on the network
/// round-trip to the distributor. The envelope is already in the
/// durable store at this point — push success / failure has no bearing
/// on delivery correctness.
pub fn wake_offline_recipient(state: Arc<AppState>, recipient_identity: String) {
    // Skip entirely if the relay was started without an ntfy URL
    // configured. This is the production-safe default for any operator
    // who hasn't deployed a distributor yet.
    if state.config.ntfy_url.is_none() {
        return;
    }

    tokio::spawn(async move {
        let topic_url = {
            let tokens = state.push_tokens.read().await;
            match tokens.get(&recipient_identity) {
                Some(url) => url.clone(),
                None => {
                    // Recipient never registered for push. Common case
                    // during transition / for users without UnifiedPush
                    // support. Silent.
                    return;
                }
            }
        };

        let result = state
            .http
            .post(&topic_url)
            // ntfy header conventions:
            // - Title/Message left empty so the OS-level notification UI
            //   on the device cannot display content even if a buggy
            //   distributor surfaces the body.
            // - Priority `min` so we use the lowest-attention class —
            //   we only want the app woken, not a banner shown.
            // - Tags none.
            .header("Title", "")
            .header("Message", "")
            .header("Priority", "min")
            .body(PUSH_PAYLOAD)
            .send()
            .await;

        match result {
            Ok(resp) if resp.status().is_success() => {
                // No identity-prefix logging. The relay generally logs
                // identity prefixes for debugging in routes.rs, but the
                // push path is high-frequency and noisy — keep this at
                // trace level.
                tracing::trace!(
                    status = %resp.status(),
                    "UnifiedPush wake-up sent"
                );
            }
            Ok(resp) => {
                tracing::debug!(
                    status = %resp.status(),
                    "UnifiedPush wake-up returned non-success — envelope still in durable store"
                );
            }
            Err(e) => {
                tracing::debug!(
                    error = %e,
                    "UnifiedPush wake-up failed — envelope still in durable store"
                );
            }
        }
    });
}
