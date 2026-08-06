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
  "attacker-resistant" rather than a trust-on-first-use downgrade from what mesh already does? This is
  probably the single biggest design decision left.
- **Wire size.** A `WireEnvelope` (signature + envelope + ciphertext) is sized for a mesh frame or a
  Wi-Fi Aware message (~255 B), not a 140-byte SMS segment. Concatenated (multipart) SMS raises the
  practical ceiling to roughly 1300 B across ~10 segments, which may or may not fit a signed envelope
  depending on message length — needs a real measurement, not an assumption, before deciding whether
  everything routes through MMS instead, or short text stays SMS and anything larger (including all
  attachments) is MMS-only.
- **No discovery, no presence.** There's no equivalent of "peer is nearby" for a phone number — a
  `SmsTransport` peer is either "has a number attached" or not, permanently, until removed. `health`
  probably just reflects whether `SEND_SMS`/`RECEIVE_SMS` permissions are granted and a SIM is present,
  not anything dynamic.
- **Default SMS app.** Some of what a full SMS integration wants (reliably intercepting incoming
  messages, sending without a system dialog) may require Android's default-SMS-app role, which is a much
  bigger ask (an app claiming that role must also handle plain/unencrypted SMS from non-Lattice numbers,
  MMS UI, etc.) than just holding `SEND_SMS`/`RECEIVE_SMS` permissions. Needs an explicit decision, not a
  default fallen into.
- **Dres's `ContactsStore.kt`** (the encrypted contacts vault) was on the original port list and never
  got an explicit port/drop call. Worth resolving alongside this, since it's contact data specifically
  for the carrier path.

## Batch log

- **Batch 1** (this): `PeerEntity.phoneNumber` + `MIGRATION_1_2`. Schema only — no transport code yet.
