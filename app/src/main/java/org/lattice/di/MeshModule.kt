package org.lattice.di

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.lattice.data.MeshBlobStore
import org.lattice.data.crypto.IdentityKeyStore
import org.lattice.mesh.CompositeMeshTransport
import org.lattice.mesh.MeshController
import org.lattice.mesh.MeshManager
import org.lattice.mesh.MeshMetrics
import org.lattice.mesh.MeshTransport
import org.lattice.mesh.StoreDigest
import org.lattice.mesh.bluetooth.BluetoothMeshTransport
import org.lattice.mesh.crypto.MessageCrypto
import org.lattice.mesh.meshExceptionHandler
import org.lattice.mesh.power.PowerMonitor
import org.lattice.mesh.power.PowerStateSource
import org.lattice.mesh.sms.SmsTransport
import org.lattice.mesh.wifiaware.WifiAwareTransport
import java.io.File

val meshModule =
    module {
        // Application-lifetime scope for the mesh engine. The shared exception handler is the process-level
        // backstop for an uncaught throw in a top-level child (e.g. a FramedLink writer coroutine).
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default + meshExceptionHandler) }
        single { MeshMetrics() }
        // Content digest of this node's syncable state; shared between the forward-store impl (maintains the
        // message set), MeshManager (folds in profile changes), and WifiAwareTransport (cues it to neighbors) —
        // a singleton so none has to construct the other (MeshManager already depends on the transport).
        single { StoreDigest() }
        // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
        single { PowerStateSource() }
        single { PowerMonitor(androidContext(), get()) }
        // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
        single { MeshBlobStore(get(), get(), File(androidContext().cacheDir, "blobtx")) }
        // Demo-screenshot builds (debug-only, `-PseedDemo=true`) swap in a no-op transport that just reports a
        // few connected neighbors (so the UI looks "connected" against the seeded data); the seam returns null
        // in release, where the demo classes don't ship (see the per-variant di/DemoWiring). Production wraps
        // every hardware-supported plane in a CompositeMeshTransport behind the single-transport seam —
        // Bluetooth LE and Wi-Fi Aware, in descending send-preference. Each plane is gated on isSupported() so
        // an unsupported one is simply absent (a device with neither yields an inert, Degraded composite).
        // Carrier fallback transport — SMS text only, no MMS (see mesh/sms/SmsTransport.kt,
        // .agents/context/sms-transport.md batch 4: batch 3's default-SMS-app claim was reverted, so this
        // is back to an inline child like the radio transports — no manifest receiver needs to reach a
        // shared instance via DI anymore, since inbound routing is this transport's own dynamic receiver.
        single<MeshTransport> {
            demoTransportOrNull() ?: run {
                val ctx = androidContext()
                val children =
                    buildList {
                        // Descending send-preference: Bluetooth (persistent links) first, then Wi-Fi Aware (ephemeral).
                        if (BluetoothMeshTransport.isSupported(ctx)) {
                            add(BluetoothMeshTransport(ctx, get(), get(), get(), get(), get()))
                        }
                        // WifiAwareTransport is @RequiresApi(31) (its NDP accept-any responder is API 31). The
                        // explicit SDK_INT guard — redundant with isSupported()'s own — is what lint reads to
                        // clear the @RequiresApi companion/constructor calls on this pre-31-reachable line.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WifiAwareTransport.isSupported(ctx)) {
                            add(WifiAwareTransport(ctx, get(), get(), get(), get(), get()))
                        }
                        // Carrier fallback, lowest send-preference (both radio planes beat it when available —
                        // SMS is the "nothing else works" path). Gated on telephony hardware only; the transport
                        // self-degrades to Unavailable at runtime if SEND_SMS/RECEIVE_SMS aren't granted or there's
                        // no SIM, same pattern as the radio transports self-degrading on a missing permission.
                        if (SmsTransport.isSupported(ctx)) {
                            add(SmsTransport(ctx, get(), get()))
                        }
                    }
                CompositeMeshTransport(children, get(), get()) { msg ->
                    android.util.Log.d("CompositeMeshTransport", msg)
                }
            }
        }
        // The E2E message cipher, built from this device's identity private keysets.
        single {
            val keys = get<IdentityKeyStore>().keys()
            MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        }
        // Constructor order: transport, messages, groups, reactions, peers, identity, settings, blobs,
        // imageScreening, blobStore, forwardStore, notifier, textModeration, messageCrypto, scope, metrics, db.
        single {
            MeshManager(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        // UI ViewModels, MeshService, and the notification/debug entry points bind this narrow facade (not
        // the concrete orchestrator) so they can be tested against a fake; the same singleton backs both keys.
        single<MeshController> { get<MeshManager>() }
    }
