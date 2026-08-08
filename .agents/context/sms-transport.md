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
- **MMS / large payloads.** Tried and reverted — see the batch 4 entry below. Still no MMS.
- **Default SMS app.** Decided (batch 2), reverted to that decision after a batch 3 detour: **not
  claimed.** `SmsTransport` registers only a dynamic `BroadcastReceiver` for `SMS_RECEIVED_ACTION`, which
  any app holding `RECEIVE_SMS` gets regardless of default-app status. This is the smaller ask and enough
  for send/receive of Lattice's own traffic; it does *not* get us MMS, reliable interception ahead of the
  real default SMS app, or the ability to suppress the system "message sent" UI chrome. Batch 3 claimed the
  role anyway to get MMS; batch 4 reverted it once it became clear that without a real plain-SMS
  conversation UI, claiming the role breaks the user's actual texting rather than adding to it. Revisit only
  alongside a genuine "Lattice as a full SMS app" UI project — see the batch 4 entry.
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
- **Batch 2**: `SmsTransport` (`mesh/sms/SmsTransport.kt`) implementing `MeshTransport` per the
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
- **Batch 3** (this): claimed the Android default-SMS-app role (per the decision above) and built real MMS.
  - `DefaultSmsRole` (`mesh/sms/DefaultSmsRole.kt`): `isDefaultSmsApp` check (`Telephony.Sms.
    getDefaultSmsPackage`) + `RoleManager`-based request-intent builder. minSdk 29 is also `RoleManager`'s
    own floor, so there's no legacy `ACTION_CHANGE_DEFAULT` fallback to carry.
  - `MmsWsp` (`mesh/sms/MmsWsp.kt`): hand-decodes the `M-Notification.ind` WSP PDU enough to extract
    transaction ID + content location. **Highest-risk, least-verified code in the project** — see its class
    doc. Only self-consistency-tested (`MmsWspTest.kt`, JVM) against a synthetic PDU built from this same
    parser's own encoding model; there's no real carrier payload available in this sandbox to validate
    against, and WSP header types this parser doesn't recognize can't be safely skipped, so it fails closed
    rather than guesses. Needs real-device testing against live carrier MMSC traffic before it's trusted;
    `com.klinkerapps:android-smsmms` (Apache-2.0, Maven Central) is the fallback if it proves unreliable.
  - `MmsSender` (`mesh/sms/MmsSender.kt`): writes an outgoing MMS as `Telephony.Mms`/`Addr`/`Part` provider
    rows (SMIL + a `application/x-lattice-wire` part carrying the base64 wire envelope), then calls
    `SmsManager.sendMultimediaMessage`. **Second-highest risk** — the provider row schema is real, stable,
    documented API, but only verified by reading the docs, not by a compile or device test.
  - `MmsWapPushReceiver` (manifest, `WAP_PUSH_DELIVER_ACTION`) + `SmsDeliverReceiver` (manifest,
    `SMS_DELIVER_ACTION`) replace batch 2's dynamically-registered receiver — both are default-app-only
    broadcasts. `SmsDeliverReceiver` adds the **safety net**: a plain SMS that doesn't decode as a Lattice
    wire envelope gets persisted to `Telephony.Sms.Inbox` (the platform's own required contract for a
    default SMS app), not silently dropped — though there's still no in-app UI to read it, see the new open
    item below. `RespondViaMessageService` is a minimal stub for `ACTION_RESPOND_VIA_MESSAGE`, one of the
    components the role requires; Lattice has no quick-response UI concept, so it just sends plain text via
    `SmsManager` directly.
  - `SmsTransport.send()` now routes an oversized payload (over `SMS_PART_CEILING` = 10 concatenated parts —
    see the wire-size measurement) through MMS instead of an ever-longer SMS chain. `sendFile` is still
    `false` — **not an MMS limitation**, see its doc: every other transport's file path rides an
    already-established per-peer encrypted session before bytes move, SMS/MMS has none, and wiring
    `MessageCrypto.seal`-style per-recipient encryption through this path is a distinct task worth its own
    pass rather than a rushed, security-relevant shortcut at the end of this batch.
  - `SmsTransport.health` now requires `DefaultSmsRole.isDefaultSmsApp` — without the role there's no
    real transport, not a degraded SMS-only one.
  - Manifest: `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `READ_SMS`; `MainActivity` gained a `SENDTO` intent-filter
    for `sms`/`smsto`/`mms`/`mmsto` (one of the role's required components).

## New open items (batch 3, superseded by batch 4 — kept for history)

- ~~No conversation UI for plain (non-Lattice) SMS/MMS.~~ Moot — batch 4 reverted the default-SMS-app claim
  this depended on. This is the exact problem that triggered the revert: with no conversation/compose UI,
  claiming the role broke the user's actual texting rather than adding to it.
- ~~`MmsWapPushReceiver` doesn't wait for the download to finish before reading parts back.~~ Moot —
  `MmsWapPushReceiver` (and MMS entirely) was removed in batch 4.
- **`sendFile`** — still open, unrelated to the MMS revert. Needs per-recipient encryption wired in before
  any transport-level file send would be safe; not specific to SMS/MMS.

## Batch 4: reverted the default-SMS-app role, removed MMS

Talked through the tradeoff directly: claiming the default-SMS-app role (batch 3) unlocked MMS for Lattice's
own protocol messages, but at the cost of the user's actual phone texting — no compose UI to send a plain
text to anyone, and incoming plain texts from real contacts landing in the safety-net's silent
`Telephony.Sms.Inbox` persistence with no screen in Lattice to ever read them. For someone who actually
made Lattice their default SMS app, the phone would look like it stopped receiving texts. That's a real
regression to ship for MMS support, not a rough edge — reverted rather than push a UI project to fix it
under time pressure.

Removed entirely: `DefaultSmsRole.kt`, `MmsWsp.kt`, `MmsSender.kt`, `MmsWapPushReceiver.kt`,
`SmsDeliverReceiver.kt`, `RespondViaMessageService.kt`, `MmsWspTest.kt`. `SmsTransport` reverted to batch 2's
shape: a dynamically-registered `SMS_RECEIVED_ACTION` receiver (courtesy-copy delivery to any
`RECEIVE_SMS`-holding app, default or not — the phone's actual default SMS app keeps working, completely
untouched, in parallel), `health` back to permission+SIM only (no `isDefaultSmsApp` check), `send()` back to
pure concatenated-SMS with no MMS routing branch. `sendFile` stays `false`, same as always.
`requiredSmsPermissions()` back to `SEND_SMS`/`RECEIVE_SMS` only. Manifest: dropped `RECEIVE_MMS`,
`RECEIVE_WAP_PUSH`, `READ_SMS`, the `SENDTO` intent-filter on `MainActivity`, and the three
default-app-only manifest components. `di/MeshModule.kt`'s `SmsTransport` binding reverted from a
standalone `single` (needed so manifest receivers could `by inject()` the same instance) back to plain
inline construction in `CompositeMeshTransport`'s children list. Onboarding's SMS section keeps just the
permissions button; the "make default" button and its strings are gone.

If MMS and a real "use Lattice like a normal SMS app" experience get built later, that's a genuine,
substantial UI project — conversation list, compose screen, thread persistence — not something to back into
via a role claim with no UI behind it. Treat it as its own deliberate feature, decided and scoped up front,
not a batch tacked onto the mesh-transport work.

## Post-batch-3 follow-up: detekt fixes, onboarding UI, real execution verification (historical — MMS since removed)

CI's `detekt` task (separate from `ktlintCheck`) failed on 8 issues after the batch-3 push — `LongMethod`
on `MmsSender.send`, `ReturnCount`/`CyclomaticComplexMethod` on `MmsWsp.parseNotificationInd`,
`ReturnCount` on `MmsWapPushReceiver.downloadAndRoute`, magic numbers in `MmsWsp`, and `ComplexCondition` on
`SmsTransport.computeHealth`. Fixed by extracting smaller functions (`MmsSender` split into
`insertMessageRow`/`insertAddrRow`/`insertParts`/`sendPdu`; `MmsWapPushReceiver` split into
`insertDownloadingRow`/`routeIfDecodable`/`decodeWireEnvelope`/`nodeIdForMessage`), a sealed `FieldOutcome`
+ extracted `Cursor` class in `MmsWsp` to collapse its branching into one `when`, named constants for the
WSP byte masks, and an intermediate `isReady` boolean in `SmsTransport.computeHealth`. **Verified against
the actual pinned tool versions, not just re-read** — downloaded the exact `dev.detekt` 2.0.0-alpha.5 CLI
jar from GitHub releases (`detekt/detekt`) and ran it locally with this repo's `config/detekt/detekt.yml`:
0 findings across every file this batch touched.

Also went back and actually **executed** (not just read) the two pure-Kotlin, Android-free files —
`SmsWireCodec` and `MmsWsp` — by pulling the Kotlin compiler from GitHub releases (`JetBrains/kotlin`),
compiling them with a standalone assertion harness mirroring `SmsWireCodecTest`/`MmsWspTest`, and running
the resulting jar: all 14 checks passed against real compiled bytecode, not reasoning-about-code. This is
the strongest verification available in this sandbox for anything Android-free; everything with an
`android.*` import still can't be compiled or run here (no Google Maven access), so those files remain
doc-verified only, same caveat as the rest of this project's low-level work.

Wired up the previously-missing onboarding UI for the default-SMS-app role (batch 3 shipped the plumbing
but nothing to trigger it): `OnboardingScreen` gained an SMS section, shown only when
`SmsTransport.isSupported` — a permissions button (`requiredSmsPermissions()`, new
`mesh/sms/SmsPermissions.kt`, mirrors `ui/Permissions.kt`'s mesh-permission pattern) and a "make default"
button (`DefaultSmsRole.requestRoleIntent` via `ActivityResultContracts.StartActivityForResult`, re-checking
`isDefaultSmsApp` on result rather than trusting the result code, since `RoleManager`'s request intent
doesn't reliably signal grant/deny across OEMs). Deliberately doesn't gate `onReady`/"Start meshing" — SMS
is opt-in, the mesh works without it. New `onboarding_sms_*` strings. Test coverage added to
`OnboardingScreenContentTest` for the section's visibility and the role button's enabled state.

