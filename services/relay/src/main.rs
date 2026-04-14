/// PHANTOM Relay — ciphertext store-and-forward service.
///
/// Trust model (ADR-004):
/// - Accepts encrypted blobs only — never inspects plaintext
/// - Short TTL per envelope
/// - Recipients fetch by envelope ID
/// - Rate limiting and quota controls
/// - No user authentication: relay is semi-trusted availability layer
///
/// This is a skeleton. Implementation follows Alpha-0 task #15 (Relay MVP).
fn main() {
    println!("phantom-relay skeleton — implementation pending (Alpha-0 task #15)");
}
