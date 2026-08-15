package org.lattice.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.lattice.ui.blocked.BlockedUsersViewModel
import org.lattice.ui.chat.ChatViewModel
import org.lattice.ui.chatlist.ChatListViewModel
import org.lattice.ui.contacts.ContactsViewModel
import org.lattice.ui.diagnostics.DiagnosticsViewModel
import org.lattice.ui.group.GroupDetailsViewModel
import org.lattice.ui.profile.ProfileDetailsViewModel
import org.lattice.ui.profile.ProfileViewModel
import org.lattice.ui.requests.MessageRequestsViewModel
import org.lattice.ui.smsrequests.SmsRequestsViewModel
import org.lattice.ui.verify.VerifyContactViewModel

val uiModule =
    module {
        // ChatViewModel takes the conversationId (the Nearby room, a peer's node id, or a group id) as a
        // runtime param; the rest (incl. GroupRepository) are resolved by type.
        viewModel { params ->
            ChatViewModel(
                params.get(),
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
                androidContext(),
            )
        }
        viewModel { ChatListViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
        viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { DiagnosticsViewModel(get(), get(), get(), get(), get()) }
        viewModel { ProfileViewModel(get(), get(), get(), get()) }
        // ProfileDetailsViewModel takes the tapped peer's node id as a runtime param.
        viewModel { params -> ProfileDetailsViewModel(params.get(), get(), get(), get(), get()) }
        // GroupDetailsViewModel takes the group id as a runtime param; the rest are resolved by type.
        viewModel { params ->
            GroupDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), androidContext())
        }
        viewModel { BlockedUsersViewModel(get(), get()) }
        viewModel { MessageRequestsViewModel(get(), get(), get(), get(), get(), androidContext()) }
        viewModel { SmsRequestsViewModel(get(), get(), get()) }
        viewModel { VerifyContactViewModel(get(), get()) }
    }
