# NOTICE

Lattice is a combined work licensed as a whole under the **GNU General Public License v3.0 or
later** (see [`COPYING`](COPYING)). It is derived from two upstream projects:

## Knit

- Source: <https://github.com/getknit/knit>
- License: GNU General Public License v3.0 or later
- The mesh transport layer (Wi-Fi Aware + BLE), wire protocol, store-and-forward, cryptographic
  identity/session handling (Tink HPKE/X25519, Ed25519 signing, AndroidKeyStore-backed keys), and
  the Compose-based UI foundation of this project originate from Knit and are used and modified
  under GPL-3.0-or-later.

## DresSecureComms

- Source: <https://github.com/DresOperatingSystems/DresSecureComms>
- License: Apache License, Version 2.0
- The carrier SMS/MMS/calling layer, Metadata Wipe, Geo Spoofer, App Lock, encrypted contacts
  vault, and Threat Scan (VirusTotal) features originate from DresSecureComms. Apache-2.0 licensed
  code may be, and here is, incorporated into a GPL-3.0-or-later combined work per Apache-2.0 §4
  and GPL-3.0 §7; the original Apache-2.0 copyright notice is preserved below as required by
  Apache-2.0 §4(c).

  > Copyright 2026 DresOS
  > Licensed under the Apache License, Version 2.0 (the "License");
  > you may not use this file except in compliance with the License.
  > You may obtain a copy of the License at
  >
  >     http://www.apache.org/licenses/LICENSE-2.0
  >
  > Unless required by applicable law or agreed to in writing, software
  > distributed under the License is distributed on an "AS IS" BASIS,
  > WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  > See the License for the specific language governing permissions and
  > limitations under the License.

## Combined work

As a whole, this repository (including the above Apache-2.0 components once folded in) is
distributed only under the terms of the GNU General Public License v3.0 or later. See
[`COPYING`](COPYING) for the full license text and [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)
for third-party library attributions inherited from Knit.
