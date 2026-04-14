# services/relay

Ciphertext store-and-forward relay. Accepts encrypted blobs, assigns TTL, serves by recipient envelope ID.
Never inspects plaintext. See ADR-004 for trust model and abuse controls.
