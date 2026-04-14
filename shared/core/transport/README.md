# shared/core/transport

Transport abstraction layer. Defines Transport, TransportSession, TransportCapabilities,
TransportHealth, TransportSelector interfaces. Implementations: relay, direct, future transports.
Upper layers must not depend on a specific transport implementation.
