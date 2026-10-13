package com.bepinex.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.modpack.ModpackMeta

/**
 * Lists all modpacks for a selected game.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModpackListScreen(
    packageName: String,
    targetGameLabel: String,
    modpacks: List<ModpackMeta>,
    activeModpackNames: Set<String>,
    onNavigateBack: () -> Unit,
    onCreateModpack: (String) -> Unit,
    onDeleteModpack: (String) -> Unit,
    onRenameModpack: (String, String) -> Unit,
    onSelectModpack: (Set<String>) -> Unit,
    onOpenModpack: (String) -> Unit,
    onExportModpack: (String) -> Unit,
    onImportModpack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.modpack_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = targetGameLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    FilledTonalIconButton(onClick = onImportModpack) {
                        Icon(
                            Icons.Filled.FileOpen,
                            contentDescription = stringResource(R.string.modpack_import),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.modpack_create), fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(16.dp)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            // Vanilla option card
            item {
                val isVanillaActive = activeModpackNames.isEmpty()
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVanillaActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = if (isVanillaActive) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectModpack(emptySet()) }
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isVanillaActive)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Block,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isVanillaActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.modpack_vanilla),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isVanillaActive) FontWeight.Bold else FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.no_mods_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = isVanillaActive,
                            onClick = { onSelectModpack(emptySet()) }
                        )
                    }
                }
            }

            // Custom modpacks section
            if (modpacks.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.FolderZip,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.no_mods_installed),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap \"+ Create Modpack\" to group and organize mods",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(modpacks, key = { it.name }) { modpack ->
                    val isActive = activeModpackNames.contains(modpack.name)
                    var menuExpanded by remember { mutableStateOf(false) }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = if (isActive) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenModpack(modpack.name) }
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isActive)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.FolderZip,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = modpack.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isActive) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = stringResource(R.string.modpack_active),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.modpack_mod_count, modpack.modCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Checkbox to toggle active state
                            Checkbox(
                                checked = isActive,
                                onCheckedChange = { checked ->
                                    val newSet = if (checked) {
                                        activeModpackNames + modpack.name
                                    } else {
                                        activeModpackNames - modpack.name
                                    }
                                    onSelectModpack(newSet)
                                }
                            )

                            // Action Menu button
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "Options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.modpack_rename)) },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = {
                                            menuExpanded = false
                                            showRenameDialog = modpack.name
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.modpack_export)) },
                                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                        onClick = {
                                            menuExpanded = false
                                            onExportModpack(modpack.name)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.delete),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            showDeleteDialog = modpack.name
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        CreateModpackDialog(
            targetGame = targetGameLabel,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreateModpack(name)
                showCreateDialog = false
            }
        )
    }

    // Rename dialog
    showRenameDialog?.let { oldName ->
        RenameModpackDialog(
            currentName = oldName,
            onDismiss = { showRenameDialog = null },
            onRename = { newName ->
                onRenameModpack(oldName, newName)
                showRenameDialog = null
            }
        )
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { name ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.modpack_confirm_delete_title)) },
            text = { Text(stringResource(R.string.modpack_confirm_delete_msg, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteModpack(name)
                    showDeleteDialog = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }
}

@Composable
fun CreateModpackDialog(
    targetGame: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_create)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.modpack_target_game, targetGame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.modpack_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        }
    )
}

@Composable
fun RenameModpackDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_rename)) },
        text = {
            Column {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.modpack_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && name.trim() != currentName) onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != currentName
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        }
    )
}
