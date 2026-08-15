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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
 * something every user is expected to have pending regularly).
 */
@Composable
fun SmsRequestsScreen(
    onBack: () -> Unit,
    viewModel: SmsRequestsViewModel = koinViewModel(),
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    SmsRequestsScreenContent(
        requests = requests,
        onAccept = viewModel::accept,
        onBlock = viewModel::block,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmsRequestsScreenContent(
    requests: List<SmsRequestRow>,
    onAccept: (nodeId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onBack: () -> Unit,
) {
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
                TextButton(onClick = {
                    onBlock()
                    showBlockConfirm = false
                }) {
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
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun SmsRequestsScreenContentEmptyPreview() =
    KnitPreview {
        SmsRequestsScreenContent(requests = emptyList(), onAccept = {}, onBlock = {}, onBack = {})
    }
