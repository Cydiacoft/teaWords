package com.teameow.teawords.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

internal data class TeaMenuOption(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val supportingText: String? = null,
    val opensMenu: Boolean = false,
    val onClick: () -> Unit
)

internal data class TeaMenuSection(val options: List<TeaMenuOption>, val label: String? = null)

/** The native Expressive popup, grouped surfaces and animated selection are shared by every menu. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TeaDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, sections: List<TeaMenuSection>) {
    val configuration = LocalConfiguration.current
    val width = minOf(252.dp, (configuration.screenWidthDp - 32).coerceAtLeast(112).dp)
    val height = (configuration.screenHeightDp * 0.75f).dp
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest) {
        Column(Modifier.width(width).heightIn(max = height).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MenuDefaults.GroupSpacing)) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes(shape = MaterialTheme.shapes.large, inactiveShape = MaterialTheme.shapes.large),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                sections.forEachIndexed { groupIndex, section ->
                    if (groupIndex > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    section.label?.let { label ->
                        MenuDefaults.DropdownMenuGroupLabel { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    section.options.forEachIndexed { index, option ->
                        val shapes = MenuDefaults.itemShape(index, section.options.size)
                        val leading: @Composable () -> Unit = { Icon(option.icon, null, Modifier.size(20.dp)) }
                        val support: (@Composable () -> Unit)? = option.supportingText?.let { text -> { Text(text) } }
                        if (option.selected != null) {
                            SelectableDropdownMenuItem(
                                selected = option.selected, onClick = option.onClick, text = { Text(option.label) }, shapes = shapes,
                                leadingIcon = leading, selectedLeadingIcon = { Icon(AppSymbols.Check, null, Modifier.size(20.dp)) },
                                supportingText = support, enabled = option.enabled, modifier = Modifier.fillMaxWidth(),
                                colors = MenuDefaults.selectableItemColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
                        } else {
                            DropdownMenuItem(onClick = option.onClick, text = { Text(option.label) }, shape = shapes.shape,
                                leadingIcon = leading, supportingText = support, enabled = option.enabled,
                                trailingContent = if (option.opensMenu) ({ Icon(AppSymbols.ChevronRight, null, Modifier.size(20.dp)) }) else null,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
