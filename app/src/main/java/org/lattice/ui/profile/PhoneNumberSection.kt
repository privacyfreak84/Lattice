package org.lattice.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.lattice.R
import org.lattice.ui.preview.KnitPreview

/**
 * "Attach a phone number" block on a peer's profile — shown only once the peer has a pinned key
 * ([ProfileDetailsUiState.hasKey], checked by the caller before rendering this): a phone number with no
 * key to encrypt to could never actually be used by [org.lattice.mesh.sms.SmsTransport], so offering it
 * earlier would be misleading rather than merely premature. Attaching/removing a number is explicitly
 * NOT a trust event (see [org.lattice.data.peer.PeerEntity.phoneNumber]) — this section is deliberately
 * separate from, and doesn't gate on, [ProfileDetailsUiState.verified].
 */
@Composable
fun PhoneNumberSection(
    displayName: String,
    phoneNumber: String?,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(phoneNumber) { mutableStateOf(phoneNumber.orEmpty()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_details_phone_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.profile_details_phone_caption, displayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().testTag("phone_number_field"),
            label = { Text(stringResource(R.string.profile_details_phone_title)) },
            placeholder = { Text(stringResource(R.string.profile_details_phone_hint)) },
            singleLine = true,
        )
        OutlinedButton(
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth().testTag("phone_number_save"),
            // A blank field has nothing to normalize/save — Remove is the action for clearing a number.
            enabled = draft.isNotBlank(),
        ) {
            Text(stringResource(R.string.profile_details_phone_save))
        }
        if (phoneNumber != null) {
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth().testTag("phone_number_remove"),
            ) {
                Text(stringResource(R.string.profile_details_phone_remove))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PhoneNumberSectionEmptyPreview() =
    KnitPreview {
        PhoneNumberSection(
            displayName = "Ada Lovelace",
            phoneNumber = null,
            onSave = {},
            onRemove = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PhoneNumberSectionSetPreview() =
    KnitPreview {
        PhoneNumberSection(
            displayName = "Grace Hopper",
            phoneNumber = "+15551234567",
            onSave = {},
            onRemove = {},
        )
    }
