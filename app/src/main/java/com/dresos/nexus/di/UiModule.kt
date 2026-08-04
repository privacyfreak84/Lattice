package com.dresos.nexus.di

import com.dresos.nexus.ui.blocked.BlockedUsersViewModel
import com.dresos.nexus.ui.chat.ChatViewModel
import com.dresos.nexus.ui.chatlist.ChatListViewModel
import com.dresos.nexus.ui.contacts.ContactsViewModel
import com.dresos.nexus.ui.diagnostics.DiagnosticsViewModel
import com.dresos.nexus.ui.group.GroupDetailsViewModel
import com.dresos.nexus.ui.profile.ProfileDetailsViewModel
import com.dresos.nexus.ui.profile.ProfileViewModel
import com.dresos.nexus.ui.requests.MessageRequestsViewModel
import com.dresos.nexus.ui.verify.VerifyContactViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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
        viewModel { VerifyContactViewModel(get(), get()) }
    }
