# PeerNetVPN transport

## Public participant network (0.2)

Public mode is off until explicitly enabled. Public contacts use
`uzel-public://host:port/targetFingerprint[/relayFingerprint]`; they contain no
private invitation secret. Direct and single-hop participant relay routes are
supported. A known reachable first participant is required; this protocol does
not create reachability when every participant is behind an inaccessible NAT.

Directory records bind a certificate, route, country, sequence and a 120-second
lifetime using SHA256withECDSA. Up to 256 records are retained and up to 32 are
sampled per exchange. Signatures authenticate the announcing key, not geography
or availability. Discovered routes are marked untrusted; DNS is checked at dial
time and the exact checked public address is used, preventing rebinding into LAN.
Explicitly imported bootstrap contacts may point to a LAN for local testing.

Relay REGISTER holds an outgoing mutual-TLS control connection. CONNECT issues
a random, target-bound, one-use ticket; ATTACH must come from the registered
target identity and registration generation within its timeout. The target
bridges only to its fixed local Node listener. Inner mutual TLS pins the target
certificate end to end. Relay and exit roles share the limit of three identities
and the same outgoing pacer. Bootstrap control is bounded independently of exit
authorization so a NAT participant can first establish its reachable route.

Public JOIN checks the authenticated client's reverse sharing route. Public-mode
generation checks and closing pending public sockets prevent deferred operations
from surviving revocation. Empty-token public authentication cannot enter private
JOIN. No public gossip operation distributes private invitation secrets.

AndroidKeyStore identity v2 permits SHA256 for directory signatures and NONE
for Conscrypt's prehashed TLS signature operation. The old v1 key is preserved;
existing invitations must be reissued with the new fingerprint after upgrade.

## Private direct protocol v1

This is an experimental, invitation-only direct transport. It is not a global
anonymous network or a claim of resistance to modified clients.

Transport: TLS 1.2 or 1.3, mutual certificates. Clients pin the invited server's
SHA-256 certificate. The server requires a valid self-signed client certificate
and a 256-bit invitation secret. A client's device identity is the SHA-256 hash
of the certificate authenticated by the TLS handshake, never a supplied label.
Certificates use EC P-256 with SHA-256. Keys are not exported from AndroidKeyStore.

Each socket sends big-endian `0x555a4c31`, a Java modified-UTF invitation secret,
and a one-byte operation. Bounded fields and connection counts apply.
Replies begin with a 32-bit status (zero = accepted). Payload forwarding begins
only after successful authentication and command admission.

- INFO: sharing boolean, measured bytes/s, current client count and country.
- PROBE: same status; used over a pinned reverse connection to verify sharing.
- MEASURE: bounded framed upload into a discard sink; no client admission needed.
  It cannot connect to any destination. At most one measurement per node.
- JOIN: client's invitation. Reverse host is replaced by the observed TLS peer
  address; the invitation fingerprint must equal the TLS client certificate.
  A long-lived control connection maintains the lease. Three simultaneous
  certificates are admitted. Periodic heartbeat and reverse checks revoke stale
  leases. No credits or traffic-volume requirements.
- TCP: destination host and port, then full-duplex byte stream. A valid control
  lease is required. DNS is resolved at the exit; all resolved addresses must pass
  the public-address policy. Actual connection uses the checked address.
- UDP: length-prefixed SOCKS5 UDP envelopes, fragment field must be zero.
  Datagram size and recent destination list are bounded. Only replies from
  requested destinations are returned.

Every data stream belongs to its control lease and is closed when the lease
ends. Reverse probes authenticate to the already observed peer, never arbitrary
third-party addresses. TCP/UDP internet targets cannot be private, loopback,
link-local, multicast, CGNAT or reserved destinations.

One aggregate pacer per server waits before each payload chunk in either
outgoing direction. There is no per-client rate budget. Calibration is a
conservative throughput estimate, not an instantaneous guarantee about the
physical uplink; tests and encrypted protocol overhead are disclosed separately.
No positive measurement means no forwarding.

The local SOCKS5 listener binds loopback and requires a per-session random
username/password, preventing other Android apps from using it without credentials.
All app-owned sockets stay outside its VPN to avoid recursive forwarding.
