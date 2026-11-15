package com.mainnetwallet.polybot.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mainnetwallet.polybot.domain.SettingField
import com.mainnetwallet.polybot.domain.SettingKind
import com.mainnetwallet.polybot.ui.components.Dot
import com.mainnetwallet.polybot.ui.components.IconBadge
import com.mainnetwallet.polybot.ui.components.Label
import com.mainnetwallet.polybot.ui.components.Pill
import com.mainnetwallet.polybot.ui.theme.Mono
import com.mainnetwallet.polybot.ui.theme.Poly
import com.mainnetwallet.polybot.ui.theme.Space

// Building blocks of the Settings pages: grouped cards with hairline-separated rows, icon badges, a radio-style
// option list instead of chips, and a floating save bar. Everything reads colours from the theme.

private const val HairlineAlpha = 0.55f
private val IconSize = 38.dp

/** Small caps title above a card, with an optional grey caption below it. It sits outside the card. */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier, caption: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Label(title)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Rounded card that holds a list of rows. Rows bring their own padding; [RowDivider] separates them. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = HairlineAlpha)),
    ) {
        Column(content = content)
    }
}

/** Hairline between two rows of a [SettingsCard], inset from the left edge. */
@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = Space.lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HairlineAlpha),
    )
}

/** Tappable row: round icon badge, title with a one-line summary, optional trailing content and a chevron. */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    titleColor: Color = Color.Unspecified,
    chevron: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        IconBadge(icon, tint, container, size = IconSize)
        Column(
            Modifier
                .weight(1f)
                .padding(start = Space.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            trailing()
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Small blue "EDITED" label shown next to a field whose draft differs from the saved value. */
@Composable
fun EditedPill() {
    val info = Poly.colors.info
    Pill("EDITED", info, info.copy(alpha = 0.16f))
}

/** Switch row. The whole row is the touch target; [icon] is optional (setting fields have none). */
@Composable
fun SettingsToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    edited: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        if (icon != null) {
            IconBadge(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = IconSize)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (edited) EditedPill()
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One editable bot setting: a switch, a number input or an option list, with its hint and any validation error. */
@Composable
fun SettingFieldRow(
    field: SettingField,
    value: String,
    error: String?,
    dirty: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    when (val kind = field.kind) {
        SettingKind.Toggle -> {
            Column {
                SettingsToggleRow(
                    title = field.label,
                    hint = field.hint,
                    checked = value == "true",
                    onChange = { onChange(it.toString()) },
                    enabled = enabled,
                    edited = dirty,
                )
                if (error != null) ErrorLine(error, Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md))
            }
        }
        is SettingKind.Number -> Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            FieldHeader(field, dirty)
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                suffix = kind.suffix?.let { { Text(it) } },
                singleLine = true,
                enabled = enabled,
                isError = error != null,
                textStyle = Mono.body,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = if (kind.integer) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) ErrorLine(error)
        }
        is SettingKind.Choice -> Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            FieldHeader(field, dirty)
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                kind.options.forEach { (id, label) ->
                    ChoiceOption(label = label, selected = value == id, enabled = enabled, onClick = { onChange(id) })
                }
            }
            if (error != null) ErrorLine(error)
        }
    }
}

@Composable
private fun FieldHeader(field: SettingField, dirty: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            if (dirty) EditedPill()
        }
        Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

/** One choice of an option list: radio button and label in a rounded outline that fills in when selected. */
@Composable
private fun ChoiceOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Floating bar with the unsaved-changes summary and the Discard and Save buttons. */
@Composable
fun SettingsSaveBar(
    changed: List<String>,
    problems: Int,
    saving: Boolean,
    canSave: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Dot(Poly.colors.warn)
                Text(
                    "${changed.size} unsaved change${if (changed.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                changed.take(4).joinToString(", ") + if (changed.size > 4) " and ${changed.size - 4} more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (problems > 0) {
                Text(
                    "$problems field${if (problems == 1) "" else "s"} need attention.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Discard") }
                Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
                }
            }
        }
    }
}
