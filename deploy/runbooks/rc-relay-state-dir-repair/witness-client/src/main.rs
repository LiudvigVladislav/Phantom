// SPDX-License-Identifier: AGPL-3.0-or-later
// PR-1b RC-RELAY-STATE-DIR-REPAIR staging witness — signed HTTP client
// for the four persistence-family handlers on the merged 27a06c8a
// image.
//
// Design principles:
//   * The four handler shapes MUST match `services/relay/src/routes.rs`
//     verbatim — this binary reimplements them so the witness cannot
//     be silently satisfied by a client bug that also lives in the
//     relay codebase.
//   * All keys are Ed25519 signing keys (see relay `prekeys::validate_
//     signing_hex`). We `Signer::sign` the exact canonical payload the
//     relay verifies against — otherwise `/prekeys/publish` returns 400
//     `BadSignature` and the witness would spuriously fail.
//   * `--key-file` persists the raw 32-byte Ed25519 secret so the
//     staging witness can reuse ONE identity across every probe (T0 +
//     48 iterations + T+24h re-check). The relay's SigningKeyBindings
//     TOFU-registers the pair once and requires it to stay stable for
//     the whole soak; a fresh key each probe would trigger
//     `SigningKeyMismatch` (409) after the first canary.
//   * Every subcommand emits ONE JSONL line on stdout so the bash
//     driver can `jq` on the result.
//
// This binary is NEVER used by production. It ships out of the
// runbook scratch directory and later moves into the Ops PR under
// `deploy/runbooks/rc-relay-state-dir-repair/witness-client/`.

use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use ed25519_dalek::{Signature, Signer, SigningKey};
use rand_core::{OsRng, RngCore};
use reqwest::Client;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::time::Duration;

/// Domain-separation label the relay's `verify_spk_signature` uses
/// (`services/relay/src/prekeys.rs`). This binary hashes the exact same
/// payload byte-for-byte.
const SPK_DOMAIN_LABEL: &[u8] = b"phantom-spk-v1";

/// HTTP call timeout. Generous — staging paths run over a local Docker
/// bridge (no middlebox) but a slow disk under load might blow through
/// a small budget. Ten seconds is enough for the worst-case sync_data +
/// parent-fsync in the merged persist path.
const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Parser)]
#[command(name = "pr1b-witness-client", disable_help_subcommand = true)]
struct Cli {
    /// Path to a 32-byte Ed25519 secret. Created on first invocation
    /// with 0600 perms (on Unix); reused afterwards so `signing_pubkey_
    /// hex` stays stable across the whole staging soak.
    #[arg(long, global = true, default_value = "/witness-key/pr1b.key")]
    key_file: PathBuf,

    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Prints the Ed25519 verify-key (hex) derived from `--key-file`,
    /// creating the file if absent. Used by the bash driver to seed
    /// `signing_pubkey_hex` for downstream commands and log it into
    /// evidence.
    PubkeyHex,

    /// POST /prekeys/publish. Builds a deterministic
    /// `SignedPreKeyPublicBundle` from `--seed`, signs it with the
    /// Ed25519 secret from `--key-file`, and publishes with
    /// `--opk-count` fresh OPKs.
    PublishPrekeys {
        #[arg(long)]
        base: String,
        #[arg(long)]
        identity_hex: String,
        #[arg(long, default_value_t = 3)]
        opk_count: usize,
        /// Deterministic seed for the SPK `key_id` + `public_key_hex`
        /// derivation. Use the probe iteration number so repeated
        /// publishes are idempotent-per-iteration but distinct across
        /// iterations.
        #[arg(long)]
        seed: u64,
    },

    /// POST /report.
    Report {
        #[arg(long)]
        base: String,
        #[arg(long)]
        reporter_hex: String,
        #[arg(long)]
        reported_hex: String,
        #[arg(long, default_value = "spam")]
        category: String,
    },

    /// POST /admin/block?token=…
    AdminBlock {
        #[arg(long)]
        base: String,
        #[arg(long)]
        admin_token: String,
        #[arg(long)]
        key_hex: String,
    },

    /// POST /push/register.
    PushRegister {
        #[arg(long)]
        base: String,
        #[arg(long)]
        identity_hex: String,
        #[arg(long)]
        topic_url: String,
    },
}

fn load_or_generate_key(path: &PathBuf) -> Result<SigningKey> {
    if path.exists() {
        let bytes = std::fs::read(path).context("read key file")?;
        if bytes.len() != 32 {
            bail!(
                "key file {} must be exactly 32 bytes; got {}",
                path.display(),
                bytes.len()
            );
        }
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&bytes);
        Ok(SigningKey::from_bytes(&arr))
    } else {
        // Parent dir must exist. The bash driver bind-mounts a per-run
        // directory that ALREADY exists on the host, so this branch
        // fires exactly once — on the first probe of a fresh run.
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).with_context(|| {
                format!("create parent dir for key file at {}", parent.display())
            })?;
        }
        let mut sk_bytes = [0u8; 32];
        OsRng.fill_bytes(&mut sk_bytes);
        let sk = SigningKey::from_bytes(&sk_bytes);
        std::fs::write(path, sk_bytes).context("write key file")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(path)?.permissions();
            perms.set_mode(0o600);
            std::fs::set_permissions(path, perms)?;
        }
        Ok(sk)
    }
}

fn http_client() -> Result<Client> {
    Client::builder()
        .timeout(HTTP_TIMEOUT)
        .user_agent("pr1b-witness-client/0.1")
        .build()
        .context("build reqwest client")
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

/// Build the exact canonical SPK payload the relay signs against.
/// `phantom-spk-v1` || 32-byte public key || 8-byte big-endian ms.
fn sign_spk(sk: &SigningKey, pubkey: &[u8; 32], created_at_ms: i64) -> String {
    let mut payload = Vec::with_capacity(SPK_DOMAIN_LABEL.len() + 32 + 8);
    payload.extend_from_slice(SPK_DOMAIN_LABEL);
    payload.extend_from_slice(pubkey);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = sk.sign(&payload);
    hex::encode(sig.to_bytes())
}

/// Deterministic OPK shape — 16-byte key_id, 32-byte public key. Both
/// derived from `(seed, index)` so repeated publishes on the same seed
/// produce IDENTICAL OPK ids (the relay `dedup_and_cap_opks` will
/// collapse duplicates), but distinct seeds produce distinct pools.
fn make_opk(seed: u64, index: usize) -> serde_json::Value {
    let mut id = [0u8; 16];
    id[..8].copy_from_slice(&seed.to_be_bytes());
    id[8] = index as u8;
    let mut pk = [0u8; 32];
    pk[..8].copy_from_slice(&seed.to_be_bytes());
    pk[8] = index as u8;
    json!({
        "key_id_hex": hex::encode(id),
        "public_key_hex": hex::encode(pk),
    })
}

fn emit(record: &serde_json::Value) {
    println!("{}", record);
}

async fn do_publish_prekeys(
    http: &Client,
    sk: &SigningKey,
    signing_pubkey_hex: &str,
    base: &str,
    identity_hex: &str,
    opk_count: usize,
    seed: u64,
) -> Result<()> {
    let mut spk_pub = [0u8; 32];
    spk_pub[..8].copy_from_slice(&seed.to_be_bytes());
    let ts = now_ms();
    let sig_hex = sign_spk(sk, &spk_pub, ts);
    let spk = json!({
        "key_id": seed as i64,
        "public_key_hex": hex::encode(spk_pub),
        "created_at_ms": ts,
        "signature_hex": sig_hex,
    });
    let opks: Vec<_> = (0..opk_count).map(|i| make_opk(seed, i)).collect();
    let body = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_pubkey_hex,
        "signed_pre_key": spk,
        "one_time_pre_keys": opks,
    });
    let url = format!("{base}/prekeys/publish");
    let resp = http.post(&url).json(&body).send().await?;
    let status = resp.status().as_u16();
    let text = resp.text().await.unwrap_or_default();
    // 200 (existing identity retry) or 201 (new identity) both count as
    // success. The relay's routes.rs returns CREATED unconditionally on
    // Ok, but we accept OK for forward-compat with any handler rework.
    let ok = matches!(status, 200 | 201);
    emit(&json!({
        "op": "prekeys-publish",
        "http_status": status,
        "ok": ok,
        "body": text,
        "ts_ms": now_ms(),
    }));
    if !ok {
        bail!("prekeys-publish returned HTTP {status}");
    }
    Ok(())
}

async fn do_report(
    http: &Client,
    base: &str,
    reporter_hex: &str,
    reported_hex: &str,
    category: &str,
) -> Result<()> {
    let body = json!({
        "reporter_key": reporter_hex,
        "reported_key": reported_hex,
        "category": category,
        "timestamp_ms": now_ms(),
    });
    let url = format!("{base}/report");
    let resp = http.post(&url).json(&body).send().await?;
    let status = resp.status().as_u16();
    let text = resp.text().await.unwrap_or_default();
    let ok = (200..300).contains(&status);
    emit(&json!({
        "op": "report",
        "http_status": status,
        "ok": ok,
        "body": text,
        "ts_ms": now_ms(),
    }));
    if !ok {
        bail!("report returned HTTP {status}");
    }
    Ok(())
}

async fn do_admin_block(
    http: &Client,
    base: &str,
    admin_token: &str,
    key_hex: &str,
) -> Result<()> {
    // Round-3 architect: encode the admin_token via reqwest's
    // `.query(...)` so any non-URL-safe byte (e.g. `&`, `+`, `#`) is
    // properly percent-encoded. The pre-round-3 shape interpolated the
    // token directly into the URL, which would break silently on a
    // legitimate `RELAY_ADMIN_TOKEN` that contained one of those chars.
    let body = json!({ "key": key_hex });
    let url = format!("{base}/admin/block");
    let resp = http
        .post(&url)
        .query(&[("token", admin_token)])
        .json(&body)
        .send()
        .await?;
    let status = resp.status().as_u16();
    let text = resp.text().await.unwrap_or_default();
    let ok = (200..300).contains(&status);
    emit(&json!({
        "op": "admin-block",
        "http_status": status,
        "ok": ok,
        "body": text,
        "ts_ms": now_ms(),
    }));
    if !ok {
        bail!("admin-block returned HTTP {status}");
    }
    Ok(())
}

async fn do_push_register(
    http: &Client,
    base: &str,
    identity_hex: &str,
    topic_url: &str,
) -> Result<()> {
    let body = json!({ "identity": identity_hex, "topic_url": topic_url });
    let url = format!("{base}/push/register");
    let resp = http.post(&url).json(&body).send().await?;
    let status = resp.status().as_u16();
    let text = resp.text().await.unwrap_or_default();
    let ok = (200..300).contains(&status);
    emit(&json!({
        "op": "push-register",
        "http_status": status,
        "ok": ok,
        "body": text,
        "ts_ms": now_ms(),
    }));
    if !ok {
        bail!("push-register returned HTTP {status}");
    }
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let http = http_client()?;
    let sk = load_or_generate_key(&cli.key_file)?;
    let signing_pubkey_hex = hex::encode(sk.verifying_key().to_bytes());

    match cli.cmd {
        Cmd::PubkeyHex => {
            // Round-3 architect: also emit a fingerprint (SHA-256 of the
            // raw pubkey bytes) so the bash driver has a stable
            // identifier to log into evidence WITHOUT exposing the
            // Ed25519 secret. The bash driver keeps the secret in a
            // persistent path OUTSIDE the evidence bundle; the
            // fingerprint is what an operator uses to notice that the
            // key file was accidentally rotated between runs.
            let pubkey_bytes = sk.verifying_key().to_bytes();
            let mut hasher = Sha256::new();
            hasher.update(pubkey_bytes);
            let fp = hex::encode(hasher.finalize());
            emit(&json!({
                "op": "pubkey-hex",
                "ok": true,
                "signing_pubkey_hex": signing_pubkey_hex,
                "signing_pubkey_fingerprint_sha256": fp,
                "ts_ms": now_ms(),
            }));
        }
        Cmd::PublishPrekeys {
            base,
            identity_hex,
            opk_count,
            seed,
        } => {
            do_publish_prekeys(
                &http,
                &sk,
                &signing_pubkey_hex,
                &base,
                &identity_hex,
                opk_count,
                seed,
            )
            .await?;
        }
        Cmd::Report {
            base,
            reporter_hex,
            reported_hex,
            category,
        } => {
            do_report(&http, &base, &reporter_hex, &reported_hex, &category).await?;
        }
        Cmd::AdminBlock {
            base,
            admin_token,
            key_hex,
        } => {
            do_admin_block(&http, &base, &admin_token, &key_hex).await?;
        }
        Cmd::PushRegister {
            base,
            identity_hex,
            topic_url,
        } => {
            do_push_register(&http, &base, &identity_hex, &topic_url).await?;
        }
    }
    Ok(())
}
