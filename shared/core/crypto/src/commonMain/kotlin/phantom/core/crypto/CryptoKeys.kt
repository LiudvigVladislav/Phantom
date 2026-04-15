package phantom.core.crypto

/**
 * Curve25519 public key used in X3DH and the Diffie-Hellman ratchet.
 * 32 bytes.
 */
@JvmInline
value class DhPublicKey(val bytes: ByteArray)

/**
 * Curve25519 private (secret) key.
 * 32 bytes.
 */
@JvmInline
value class DhPrivateKey(val bytes: ByteArray)

/** A matched Curve25519 keypair. */
data class DhKeyPair(
    val publicKey: DhPublicKey,
    val privateKey: DhPrivateKey,
)

/** 32-byte symmetric chain key used inside the KDF chain. */
@JvmInline
value class ChainKey(val bytes: ByteArray)

/** 32-byte per-message symmetric key derived from the chain key. */
@JvmInline
value class MessageKey(val bytes: ByteArray)

/** 32-byte root key shared between both parties after X3DH. */
@JvmInline
value class RootKey(val bytes: ByteArray)
