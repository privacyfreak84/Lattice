package org.lattice.ui.smsrequests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.lattice.R
import org.lattice.ui.components.Avatar
import org.lattice.ui.preview.KnitPreview

/**
 * The SMS Requests inbox — see [SmsRequestsViewModel]'s class doc. Reached from the chat list's overflow
 * menu (not a badge like Message Requests: this is a less frequent, opt-into-SMS-fallback feature, not
 * something every user is expected to have pending regularly). The FAB is the cold-start entry point —
 * [SmsRequestsViewModel.initiate] — for a number the user already has out of band, separate from the
 * per-row Accept action for someone who texted *us* first.
 */
@Composable
fun SmsRequestsScreen(
    onBack: () -> Unit,
    viewModel: SmsRequestsViewModel = koinViewModel(),
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val initiateResult by viewModel.initiateResult.collectAsStateWithLifecycle()
    val acceptResult by viewModel.acceptResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val sentMessage = stringResource(R.string.sms_requests_sent)
    val invalidMessage = stringResource(R.string.sms_requests_invalid_number)
    val sendFailedMessage = stringResource(R.string.sms_requests_send_failed)
    val acceptNotFoundMessage = stringResource(R.string.sms_requests_accept_not_found)
    LaunchedEffect(initiateResult) {
        when (initiateResult) {
            SmsInitiateResult.SENT -> snackbarHostState.showSnackbar(sentMessage)
            SmsInitiateResult.INVALID -> snackbarHostState.showSnackbar(invalidMessage)
            SmsInitiateResult.SEND_FAILED -> snackbarHostState.showSnackbar(sendFailedMessage)
            null -> Unit
        }
        if (initiateResult != null) viewModel.consumeInitiateResult()
    }
    LaunchedEffect(acceptResult) {
        when (acceptResult) {
            SmsAcceptResult.NOT_FOUND -> snackbarHostState.showSnackbar(acceptNotFoundMessage)
            SmsAcceptResult.SEND_FAILED -> snackbarHostState.showSnackbar(sendFailedMessage)
            null -> Unit
        }
        if (acceptResult != null) viewModel.consumeAcceptResult()
    }

    SmsRequestsScreenContent(
        requests = requests,
        onAccept = viewModel::accept,
        onBlock = viewModel::block,
        onInitiate = viewModel::initiate,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmsRequestsScreenContent(
    requests: List<SmsRequestRow>,
    onAccept: (nodeId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onInitiate: (phoneNumber: String) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.sms_requests_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("sms_requests_add_fab"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sms_requests_add_title))
            }
        },
    ) { padding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.sms_requests_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(requests, key = { it.nodeId }) { row ->
                    SmsRequestRowItem(
                        row = row,
                        onAccept = { onAccept(row.nodeId) },
                        onBlock = { onBlock(row.nodeId) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSmsContactDialog(
            onDismiss = { showAddDialog = false },
            onSend = { number ->
                onInitiate(number)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddSmsContactDialog(
    onDismiss: () -> Unit,
    onSend: (phoneNumber: String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sms_requests_add_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sms_requests_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().testTag("sms_requests_add_field"),
                    label = { Text(stringResource(R.string.sms_requests_add_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(draft) },
                modifier = Modifier.testTag("sms_requests_add_send"),
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.sms_requests_add_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SmsRequestRowItem(
    row: SmsRequestRow,
    onAccept: () -> Unit,
    onBlock: () -> Unit,
) {
    var showBlockConfirm by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("sms_request_row_${row.nodeId}")
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = row.avatarHash, name = row.title, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.phoneNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(
            onClick = { showBlockConfirm = true },
            modifier = Modifier.testTag("sms_request_block_${row.nodeId}"),
        ) {
            Text(stringResource(R.string.message_requests_block), color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = onAccept,
            modifier = Modifier.testTag("sms_request_accept_${row.nodeId}"),
        ) {
            Text(stringResource(R.string.message_requests_accept))
        }
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(stringResource(R.string.message_requests_block_confirm_title)) },
            text = { Text(stringResource(R.string.message_requests_block_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBlock()
                        showBlockConfirm = false
                    },
                    modifier = Modifier.testTag("sms_request_block_confirm_${row.nodeId}"),
                ) {
                    Text(
                        text = stringResource(R.string.message_requests_block),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SmsRequestsScreenContentPreview() =
    KnitPreview {
        SmsRequestsScreenContent(
            requests =
                listOf(
                    SmsRequestRow(nodeId = "n1", title = "Unknown", avatarHash = null, phoneNumber = "+15551234567"),
                    SmsRequestRow(nodeId = "n2", title = "Sam", avatarHash = null, phoneNumber = "+15559876543"),
                ),
            onAccept = {},
            onBlock = {},
            onInitiate = {},
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun SmsRequestsScreenContentEmptyPreview() =
    KnitPreview {
        SmsRequestsScreenContent(requests = emptyList(), onAccept = {}, onBlock = {}, onInitiate = {}, onBack = {})
    }
