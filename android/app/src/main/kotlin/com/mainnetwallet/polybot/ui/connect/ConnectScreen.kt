package com.mainnetwallet.polybot.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.data.net.StepState
import com.mainnetwallet.polybot.data.net.TargetParse
import com.mainnetwallet.polybot.data.net.TestStep
import com.mainnetwallet.polybot.data.net.UrlUtils
import com.mainnetwallet.polybot.data.net.PcTarget
import com.mainnetwallet.polybot.ui.LocalGraph
import com.mainnetwallet.polybot.ui.LocalUi
import com.mainnetwallet.polybot.ui.components.PolyCard
import com.mainnetwallet.polybot.ui.components.ProCard
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * First-run login and the "Connection" editor in Settings: PC address (https URL, http for LAN, or gist:<id>),
 * API token, a step-by-step connection test and Save.
 */
@Composable
fun ConnectScreen(
    initialUrl: String,
    initialToken: String,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
    embedded: Boolean = false,
    onDone: () -> Unit = {},
) {
    val graph = LocalGraph.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val localNetworkGate = rememberLocalNetworkGate { ui.message(LOCAL_NETWORK_DENIED_MESSAGE, long = true) }

    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var showToken by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<TestStep>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val parsed = UrlUtils.parseTarget(url)
    val insecure = (parsed as? TargetParse.Ok)?.target.let { target ->
        target is PcTarget.Direct && UrlUtils.isInsecureOutsidePrivate(target.baseUrl)
    }
    val addressError = if (url.isNotBlank() && parsed is TargetParse.Invalid) parsed.reason else null
    val canSave = parsed is TargetParse.Ok && token.isNotBlank() && !saving && !testing

    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme

    // Embedded in the main scaffold the insets are already handled by the bars around it.
    val insets = if (embedded) Modifier.imePadding() else Modifier.safeDrawingPadding().imePadding()
    Column(
        modifier
            .fillMaxSize()
            .then(insets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xl, vertical = if (firstRun) Space.xl else Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The content column stays phone-width on tablets and landscape.
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            if (firstRun) {
                Spacer(Modifier.height(Space.xl))
                BrandHeader()
                Spacer(Modifier.height(Space.sm))
            } else {
                Text("Connection", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "The address and API token the app uses to reach your PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            ProCard(spacing = Space.lg) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("PC address") },
                    placeholder = { Text("gist:<id> or https://…") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    singleLine = true,
                    isError = addressError != null,
                    supportingText = {
                        Text(addressError ?: "gist:<id>, an https:// URL, or http://192.168.x.x:8000 on your home network")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API token") },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    supportingText = { Text("API_TOKEN from the PC's .env file") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token",
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (insecure) {
                PolyCard(container = Poly.colors.warnContainer) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Poly.colors.warn)
                        Text(
                            "This address uses plain http outside your home network. Your token and all data would travel " +
                                "unencrypted. Use an https:// address or a Gist link instead.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (steps.isNotEmpty()) {
                ProCard(spacing = Space.md) {
                    Text("Connection test", style = MaterialTheme.typography.titleSmall)
                    steps.forEach { StepRow(it) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Button(
                    onClick = {
                        focus.clearFocus()
                        saving = true
                        localNetworkGate(url) {
                            scope.launch {
                                graph.prefsStore.saveConnection(url, token)
                                saving = false
                                ui.message("Saved. Connecting…")
                                onDone()
                            }
                        }
                    },
                    enabled = canSave,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    } else {
                        Text(if (firstRun) "Save and connect" else "Save", style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    onClick = {
                        focus.clearFocus()
                        testing = true
                        steps = emptyList()
                        localNetworkGate(url) {
                            scope.launch {
                                val ok = graph.connectionTester.run(url, token.trim()) { steps = it }
                                testing = false
                                ui.message(if (ok) "Connection works." else "Connection test failed. See the steps above.", long = !ok)
                            }
                        }
                    },
                    enabled = !testing && !saving && url.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Test connection", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** First-run brand block: gradient app mark, name and one line saying what the app connects to. */
@Composable
private fun BrandHeader() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(scheme.primary, Poly.colors.ringStage))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ShowChart, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(Space.sm))
        Text("Polybot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect to the bot running on your PC to monitor and control it from here.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepRow(step: TestStep) {
    val colors = Poly.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.Top) {
        when (step.state) {
            StepState.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            StepState.Ok -> Icon(Icons.Filled.CheckCircle, contentDescription = "OK", tint = colors.up, modifier = Modifier.size(20.dp))
            StepState.Failed -> Icon(Icons.Filled.Error, contentDescription = "Failed", tint = colors.down, modifier = Modifier.size(20.dp))
            StepState.Skipped -> Icon(Icons.Filled.Remove, contentDescription = "Skipped", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            StepState.Pending -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "Waiting", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall)
            if (step.detail.isNotBlank()) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.state == StepState.Failed) colors.down else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
