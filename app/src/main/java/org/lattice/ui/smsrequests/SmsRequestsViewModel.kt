package org.lattice.ui.smsrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lattice.data.PeerRepository
import org.lattice.data.settings.SettingsStore
import org.lattice.identity.displayNameFor
import org.lattice.mesh.sms.SmsBootstrap

/** One pending SMS-only-contact request — see [org.lattice.data.peer.PeerDao.observePendingSmsRequests]. */
data class SmsRequestRow(
    val nodeId: String,
    val title: String,
    val avatarHash: String?,
    val phoneNumber: String,
)

/**
 * The SMS Requests inbox: peers who texted us first over the SMS-only-contact bootstrap flow (see
 * [org.lattice.mesh.sms.SmsTransport]'s class doc and `.agents/context/sms-transport.md` batch 5),
 * pinned unverified, awaiting an explicit user decision before we reciprocate. Deliberately separate from
 * [org.lattice.ui.requests.MessageRequestsViewModel]'s DM/group inbox: a first-contact `PROFILE` frame
 * isn't a message thread, so it wouldn't appear there.
 *
 * Per row: **Accept** ([SmsBootstrap.accept] — sends our profile to their number, clears the request) or
 * **Block** (mirrors [org.lattice.ui.requests.MessageRequestsViewModel.block] — no delete/decline-only
 * option, since there's no thread here to clear; blocking is the only way to make an unwanted request
 * stop reappearing, and matches every other "decline a stranger" action in this app).
 */
class SmsRequestsViewModel(
    private val peers: PeerRepository,
    private val bootstrap: SmsBootstrap,
    private val settings: SettingsStore,
) : ViewModel() {
    val requests: StateFlow<List<SmsRequestRow>> =
        combine(
            peers.observePendingSmsRequests(),
            settings.blockedNodeIds,
        ) { pending, blocked ->
            // block() only touches SettingsStore's blocked set, never the peers row itself (mirrors
            // MessageRequestsViewModel's own isRequest() filter) -- without this, a just-blocked request
            // would sit in the query result forever, reappearing here even though InboundPipeline now
            // refuses everything further from that sender.
            pending
                .filter { it.nodeId !in blocked }
                .map { peer ->
                    SmsRequestRow(
                        nodeId = peer.nodeId,
                        title = displayNameFor(peer.name, peer.nodeId),
                        avatarHash = peer.avatarHash,
                        // Non-null by construction — observePendingSmsRequests only ever returns rows
                        // with phoneNumber IS NOT NULL.
                        phoneNumber = peer.phoneNumber!!,
                    )
                }.sortedBy { it.title }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accepts [nodeId]'s request: sends our profile to their number, clearing it from [requests]. */
    fun accept(nodeId: String) {
        viewModelScope.launch { bootstrap.accept(nodeId) }
    }

    /** Declines by blocking — see the class doc for why there's no separate delete-only option here. */
    fun block(nodeId: String) {
        viewModelScope.launch { settings.block(nodeId, peers.find(nodeId)?.deviceTag) }
    }
}
