# SMS/MMS carrier transport — design notes and open questions

Working notes for the SMS/MMS transport, built in small batches. Read this before touching anything
under `mesh/sms/` (once it exists) or `data/peer/PeerEntity.kt`'s `phoneNumber` field.

## Why this exists

The mesh transports (`mesh/wifiaware/`, `mesh/bluetooth/`) only work when a peer is in radio range —
typically a few hundred meters. SMS/MMS rides the carrier network instead: it works anywhere there's any
cell signal, needs no data plan (SMS/MMS ride the signaling channel, not the data connection, so they're
billed and provisioned separately and often survive when data doesn't), and gets carrier priority over
data traffic. The two transports cover structurally different failure modes — mesh covers "nearby, zero
infrastructure"; SMS covers "far apart, or infrastructure is degraded, but the phone network itself still
works" — which is the actual point of adding it, not "one more transport for its own sake".

Plain SMS is unencrypted in transit (carrier-visible, same as it's been since the 90s). The goal here is
specifically to run Lattice's real end-to-end crypto (`mesh/crypto/` — X25519/Tink, the same stack mesh
uses) *over* SMS as the wire, not to build a second, weaker crypto scheme for it the way Dres's original
static-passphrase `SmsCrypto` did. The carrier still sees that a message was sent and roughly how large —
that's inherent to SMS and can't be hidden — but not its content.

## The core tension: `MeshTransport` is radio-shaped, SMS isn't

`mesh/MeshTransport.kt` is the seam every transport implements, and it's deliberately shaped around
*proximity radios*: `neighbors`/`reachable` (who's nearby right now), `health` (is the radio on and
working), `fastFanout`/`fastSend` (a message-channel coordination plane with no data path), `sendDigest`
(store-and-forward custody diffing over a live link), `radioContended`/`highThroughput` (radio-specific
routing hints). SMS has none of this: no discovery (you already know the number), no persistent link, no
"radio health" beyond "does this device have an SMS-capable SIM", and a payload ceiling of ~140 bytes per
raw SMS segment (MMS for anything larger).

Decision: implement `MeshTransport` anyway, rather than inventing a parallel non-radio transport
abstraction. Most of the interface already has sensible defaults for exactly this shape (`hasFastPlane`,
`highThroughput`, `radioContended`, `fastFanout`, `fastSend`, `sendDigest` all default to "off/no-op" —
see the fakes and the demo transport for precedent). The real work for `SmsTransport` is `start`/`stop`
(register/unregister the `SmsReceiver`), `send`/`sendFile` (encode a `WireEnvelope` onto SMS/MMS and hand
it to `SmsManager`), and `inbound` (decode an incoming SMS/MMS back into a `WireEnvelope`). `neighbors`/
`reachable` for this transport means "peers with a `phoneNumber` attached", not a live radio sighting —
worth flagging clearly in the class doc when it's written, since every other implementation's reading of
those two properties is proximity-based and this one deliberately isn't.

## Addressing: phone number is a routing hint, never identity

Mesh identity is 100% `nodeId` (a cryptographic identity — see `mesh/crypto/`), with `verified` (safety
number / QR) as the only thing that carries authentication. SMS needs a phone number to address a message
at the carrier level, but a phone number must never become a second identity system living alongside
`nodeId` — that would split "who is this peer" into two answers that can disagree.

Landed (batch 1, `PeerEntity.phoneNumber`, migration `MIGRATION_1_2`): an optional, nullable phone-number
field on the existing `nodeId`-keyed `peers` row. Attaching a number is explicitly *not* a trust event —
it doesn't touch `verified` or `pubKey`, and nothing populates it automatically from any transport (a
nodeId carries no phone number; one should never be inferred). `NULL` means "mesh-only", and `SmsTransport`
skips a peer with no number the same way any transport skips a peer it can't reach.

## Open questions for later batches (not yet decided)

- **Bootstrapping an SMS-only contact.** Today every peer relationship starts via mesh proximity + a
  safety-number verification. Is a phone-number-only contact (never met over mesh, no `pubKey` yet)
  supported at all, and if so, how does the *first* message — which needs to carry or negotiate a public
  key before anything can be E2E-encrypted — fit inside an SMS payload and stay believable as
  "attacker-resistant" rather than a trust-on-first-use downgrade from what mesh already does? Still open;
  `SmsTransport` (batch 2) only ever surfaces peers that already have both a `phoneNumber` and a pinned
  `pubKey`, same trust model as every other transport — an SMS-only contact simply isn't representable yet.
- **MMS / large payloads.** Still open. `SmsTransport.sendFile` always returns `false` in batch 2 — see the
  wire-size measurement below for why a single-recipient DM fits SMS but attachments and larger group
  messages don't.
- **Default SMS app.** Decided for now (batch 2): **not claimed.** `SmsTransport` registers only a dynamic
  `BroadcastReceiver` for `SMS_RECEIVED_ACTION`, which any app holding `RECEIVE_SMS` gets regardless of
  default-app status. This is the smaller ask and enough for send/receive of Lattice's own traffic; it does
  *not* get us reliable interception ahead of the real default SMS app, or the ability to suppress the
  system "message sent" UI chrome. Revisit if that turns out to matter in practice.
- **Dres's `ContactsStore.kt`** — still unresolved, not touched by batch 2.
- **Phone number normalization.** `SmsTransport.onSmsReceived` matches an inbound SMS's `originatingAddress`
  against the stored `phoneNumber` with an exact string comparison — no E.164 normalization. A correctly
  configured peer whose carrier delivers a differently-formatted address (spacing, missing/extra country
  code) will silently fail to match. Needs a real normalization scheme, tested against actual carrier
  behavior, not a guessed one.

## Wire-size measurement (resolved, batch 2)

Couldn't get a compiled measurement in this sandbox (no Maven/Google repo access for
`kotlinx-serialization-cbor`, same constraint as the rest of the build) — this is a hand-computed estimate
from the actual field shapes in `Wire.kt` / `MessageCrypto.kt`, not a golden-vector run. Worth re-deriving
from a real `WireCodec.encodeWire()` call once this can build for real.

For a short single-recipient DM ("Hey, see you at 6"):
- `EncEnvelope`: `nonce` (12 B) + `ct` (~30 B plaintext + 16 B GCM tag ≈ 46 B) + one `WrappedKey`
  (`to` nodeId ~44–66 B + Tink hybrid-wrapped 32 B content key ≈ 100–140 B) + CBOR map overhead ≈ **~250 B**.
- `RelayEnvelope` wrapping it (`type`, `id`, `senderId`, `sentAt`, `recipientId`, the payload above, map
  overhead) ≈ **~410 B**.
- `WireEnvelope` wrapping *that* (`ttl`/`hops`/`relay`, 64 B Ed25519 `sig`, the `signed` bytes above) ≈
  **~495 B**.

Base64'd for SMS transport (`SmsWireCodec.encode`), ~495 B → ~660 chars. At the GSM-7 concatenated budget
(153 chars/part, `SmsWireCodec.GSM7_CONCAT_PART_CHARS`) that's **5 parts** — comfortably inside the ~10-part
practical ceiling this doc flagged as needing a real number.

The number that doesn't scale well: every additional group recipient adds one more `WrappedKey`
(~100–140 B) to the payload. A 3-recipient group message is already pushing 7–8 parts; a 5+ recipient group
message will blow past the ceiling. Decision for this batch: `SmsTransport` doesn't special-case this —
`send()` just encodes and calls `SmsManager.divideMessage`/`sendMultipartTextMessage`, so an oversized
message degrades to "many SMS parts" rather than failing outright, but a real group-size cutoff (route to
MMS, or refuse and tell the user) is still an open call for whenever MMS lands.

## Batch log

- **Batch 1**: `PeerEntity.phoneNumber` + `MIGRATION_1_2`. Schema only — no transport code yet.
- **Batch 2** (this): `SmsTransport` (`mesh/sms/SmsTransport.kt`) implementing `MeshTransport` per the
  "implement the interface anyway" decision above — text-only, addressed to peers with both `phoneNumber`
  and a pinned `pubKey`. `SmsWireCodec` (`mesh/sms/SmsWireCodec.kt`) is the pure-logic base64 framing +
  part-count estimator, unit-tested on the JVM. Wired into `CompositeMeshTransport` last (lowest
  send-preference, after both radio planes) in `di/MeshModule.kt`, gated on `FEATURE_TELEPHONY` at
  construction and self-degrading to `Unavailable` health at runtime if `SEND_SMS`/`RECEIVE_SMS` aren't
  granted or there's no SIM. Added `PeerDao.observeWithPhoneNumber()` / `PeerRepository.observeWithPhoneNumber()`
  as the routing-table source. Manifest: `SEND_SMS`, `RECEIVE_SMS`, `android.hardware.telephony`
  (`required=false`). Not done: MMS/`sendFile` (always `false`), `SmsReceiver` UI/permission-request flow
  (the transport registers its own dynamic receiver, but nothing yet prompts the user for the two
  permissions), `CallManager`, the encrypted contacts vault. All still open per the list above.

