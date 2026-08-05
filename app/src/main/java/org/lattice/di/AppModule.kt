package org.lattice.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.lattice.data.AttachmentStore
import org.lattice.data.AvatarStore
import org.lattice.data.BlobRepository
import org.lattice.data.GallerySaver
import org.lattice.data.GroupRepository
import org.lattice.data.LatticeDatabase
import org.lattice.data.MessageRepository
import org.lattice.data.PeerRepository
import org.lattice.data.ReactionRepository
import org.lattice.data.crypto.DatabaseKey
import org.lattice.data.crypto.IdentityKeyStore
import org.lattice.data.crypto.KeystoreSecret
import org.lattice.data.forward.ForwardRepository
import org.lattice.data.settings.SettingsStore
import org.lattice.demo.DemoComposer
import org.lattice.identity.AndroidDeviceIdSource
import org.lattice.identity.DeviceIdSource
import org.lattice.identity.Identity
import org.lattice.mesh.ForwardStore
import org.lattice.notifications.MessageNotifier
import org.lattice.notifications.Notifier
import org.lattice.review.ReviewPrompter
import org.lattice.ui.RouteInbox
import org.lattice.ui.review.ReviewPromptInbox
import org.lattice.ui.share.ShareInbox

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create {
                androidContext().preferencesDataStoreFile("knit_settings")
            }
        }
        single { SettingsStore(get()) }
        // Stable per-device id (ANDROID_ID) — seeds the soft block-continuity DeviceTag, not the nodeId.
        single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
        // E2E identity keypair, wrapped under a hardware AndroidKeyStore key in filesDir (outside the DB).
        single { IdentityKeyStore(KeystoreSecret(androidContext(), "knit_identity_key", "identity.key")) }
        // nodeId is derived from the keypair's public bundle; the device id only feeds the block tag.
        single { Identity(get(), get()) }
        single { AvatarStore(androidContext(), get()) }
        single { AttachmentStore(androidContext(), get(), get()) }
        single { GallerySaver(androidContext()) }
        single<Notifier> { MessageNotifier(androidContext()) }
        // Single-shot handoff for content arriving via the system share sheet (ACTION_SEND).
        single { ShareInbox() }
        // Debug trailer seam driving the real Nearby composer (see DemoComposer). Inert in every build
        // unless the debug DemoDirector emits into it; R8 strips it from release.
        single { DemoComposer() }
        // Single-shot handoff for a notification-tap deep-link route (drained by KnitApp).
        single { RouteInbox() }
        // Single-shot signal that the rate/review prompt should show (drained by KnitApp).
        single { ReviewPromptInbox() }
        // Decides when to ask for an app rating and where to route it (installer-aware); no-op in demo builds.
        single { ReviewPrompter(androidContext(), get(), get(), get(), get()) }

        single { DatabaseKey(androidContext()) }
        single { LatticeDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
        single { get<LatticeDatabase>().messageDao() }
        single { get<LatticeDatabase>().peerDao() }
        single { get<LatticeDatabase>().reactionDao() }
        single { get<LatticeDatabase>().blobDao() }
        single { get<LatticeDatabase>().groupDao() }
        single { get<LatticeDatabase>().blobVerdictDao() }
        single { get<LatticeDatabase>().forwardDao() }
        single { MessageRepository(get()) }
        single { PeerRepository(get()) }
        single { ReactionRepository(get(), get()) }
        // BlobRepository: blobDao, messageDao, peerDao, settings, blobVerdictDao, groupDao, forwardDao, db.
        single { BlobRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { GroupRepository(get(), get(), get()) }
        // Store-and-forward custody for DMs, backed by the encrypted forward_store table. Takes the shared
        // StoreDigest (from meshModule) so every carry-store mutation keeps the cue-plane content digest in sync,
        // plus the LatticeDatabase so store/remove/sweep run their DB writes in a transaction under the repo mutex.
        single<ForwardStore> { ForwardRepository(get(), get(), get()) }
    }
