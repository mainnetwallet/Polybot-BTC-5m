package com.mainnetwallet.polybot.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

/** "Are you sure?" for every destructive or irreversible action. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks the person to type a confirmation phrase. The text is sent exactly as typed ([requiredExact] only
 * gates the button for client-checked phrases such as RESET ALL); the server's error is shown in the dialog.
 */
@Composable
fun PhraseDialog(
    title: String,
    body: String,
    confirmLabel: String,
    hint: String,
    onSubmit: suspend (String) -> ActionResult,
    onDismiss: () -> Unit,
    requiredExact: String? = null,
    danger: Boolean = true,
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val accent = if (danger) Poly.colors.live else MaterialTheme.colorScheme.primary
    val canSend = !busy && typed.isNotBlank() && (requiredExact == null || typed == requiredExact)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(body)
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        error = null
                    },
                    label = { Text(hint) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(
                enabled = canSend,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.surface),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = onSubmit(typed)
                        busy = false
                        if (result.ok) {
                            ui.report(result)
                            onDismiss()
                        } else {
                            ui.strong()
                            error = result.message
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                else Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Live trading is on: a manual sell moves real money, so it gets its own bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSellSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: suspend () -> ActionResult,
    onDismiss: () -> Unit,
) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text("LIVE: $title", style = MaterialTheme.typography.titleLarge, color = Poly.colors.live)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            Text(
                "This sends a real market order with real money.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Poly.colors.live, contentColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = onConfirm()
                            busy = false
                            ui.report(result)
                            onDismiss()
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                    else Text(confirmLabel)
                }
            }
        }
    }
}
