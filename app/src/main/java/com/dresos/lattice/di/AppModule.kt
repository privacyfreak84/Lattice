package com.dresos.lattice.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dresos.lattice.data.AttachmentStore
import com.dresos.lattice.data.AvatarStore
import com.dresos.lattice.data.BlobRepository
import com.dresos.lattice.data.GallerySaver
import com.dresos.lattice.data.GroupRepository
import com.dresos.lattice.data.LatticeDatabase
import com.dresos.lattice.data.MessageRepository
import com.dresos.lattice.data.PeerRepository
import com.dresos.lattice.data.ReactionRepository
import com.dresos.lattice.data.crypto.DatabaseKey
import com.dresos.lattice.data.crypto.IdentityKeyStore
import com.dresos.lattice.data.crypto.KeystoreSecret
import com.dresos.lattice.data.forward.ForwardRepository
import com.dresos.lattice.data.settings.SettingsStore
import com.dresos.lattice.demo.DemoComposer
import com.dresos.lattice.identity.AndroidDeviceIdSource
import com.dresos.lattice.identity.DeviceIdSource
import com.dresos.lattice.identity.Identity
import com.dresos.lattice.mesh.ForwardStore
import com.dresos.lattice.notifications.MessageNotifier
import com.dresos.lattice.notifications.Notifier
import com.dresos.lattice.review.ReviewPrompter
import com.dresos.lattice.ui.RouteInbox
import com.dresos.lattice.ui.review.ReviewPromptInbox
import com.dresos.lattice.ui.share.ShareInbox
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

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
