package com.example.photowallpaper.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.photowallpaper.api.DriveFile
import com.example.photowallpaper.preferences.AppPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSignInClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isEnabled by viewModel.isEnabled.collectAsState()
    val intervalMinutes by viewModel.intervalMinutes.collectAsState()
    val lastChanged by viewModel.lastChanged.collectAsState()
    val folderName by viewModel.folderName.collectAsState()
    val blurHome by viewModel.blurHomePercent.collectAsState()
    val blurLock by viewModel.blurLockPercent.collectAsState()
    val landscapeOnly by viewModel.landscapeOnly.collectAsState()
    val folderPickerState by viewModel.folderPickerState.collectAsState()

    // Folder picker dialog
    if (folderPickerState.isOpen) {
        FolderPickerDialog(
            state = folderPickerState,
            onNavigateToFolder = viewModel::navigateToFolder,
            onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
            onSelectFolder = viewModel::selectFolder,
            onSelectCurrentFolder = viewModel::selectCurrentFolder,
            onDismiss = viewModel::closeFolderPicker
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Wallpaper") },
                actions = {
                    if (uiState.isSignedIn) {
                        IconButton(onClick = { viewModel.signOut() }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Sign out")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.isSignedIn) {
                SignedOutContent(onSignInClick)
            } else if (!uiState.hasPermission) {
                PermissionMissingContent(
                    onGrantPermissionClick = onSignInClick,
                    onRevokeAndRetryClick = { viewModel.revokeAccessAndRetry() }
                )
            } else {
                SignedInContent(
                    uiState = uiState,
                    isEnabled = isEnabled,
                    intervalMinutes = intervalMinutes,
                    lastChanged = lastChanged,
                    folderName = folderName,
                    blurHomePercent = blurHome,
                    blurLockPercent = blurLock,
                    landscapeOnly = landscapeOnly,
                    onToggleEnabled = viewModel::setEnabled,
                    onIntervalChanged = viewModel::setInterval,
                    onChangeNow = viewModel::changeWallpaperNow,
                    onRefreshPhotos = viewModel::loadPhotos,
                    onBrowseFolders = viewModel::openFolderPicker,
                    onBlurHomeChanged = viewModel::setBlurHome,
                    onBlurLockChanged = viewModel::setBlurLock,
                    onLandscapeOnlyChanged = viewModel::setLandscapeOnly
                )
            }

            uiState.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ── Folder Picker Dialog ─────────────────────────────────────────────

@Composable
private fun FolderPickerDialog(
    state: FolderPickerState,
    onNavigateToFolder: (DriveFile) -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onSelectFolder: (DriveFile) -> Unit,
    onSelectCurrentFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                // Breadcrumb navigation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.breadcrumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = crumb.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == state.breadcrumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == state.breadcrumbs.lastIndex)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { onNavigateToBreadcrumb(index) }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Folder list
                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Loading folders...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (state.error != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (state.folders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No subfolders here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                    ) {
                        items(state.folders, key = { it.id }) { folder ->
                            FolderRow(
                                folder = folder,
                                onOpen = { onNavigateToFolder(folder) },
                                onSelect = { onSelectFolder(folder) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSelectCurrentFolder) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Use '${state.breadcrumbs.last().name}'")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FolderRow(
    folder: DriveFile,
    onOpen: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder icon (unicode)
        Text(
            text = "\uD83D\uDCC1",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.width(12.dp))

        // Folder name
        Text(
            text = folder.name ?: "Unnamed",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Select button (picks this folder without navigating into it)
        TextButton(onClick = onSelect) {
            Text("Select", style = MaterialTheme.typography.labelMedium)
        }

        // Arrow to navigate into subfolder
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open folder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Signed Out ──────────────────────────────────────────────────────

@Composable
private fun SignedOutContent(onSignInClick: () -> Unit) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = "Photo Wallpaper",
        style = MaterialTheme.typography.headlineLarge
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Automatically set random photos from your Google Drive as your wallpaper",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Create a folder in Google Drive, upload your photos there, and this app will rotate them as your wallpaper.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(onClick = onSignInClick) {
        Text("Sign in with Google")
    }
}

// ── Permission Missing ──────────────────────────────────────────────

@Composable
private fun PermissionMissingContent(
    onGrantPermissionClick: () -> Unit,
    onRevokeAndRetryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(48.dp))

    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Permission Required",
        style = MaterialTheme.typography.headlineMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "This app needs read access to your Google Drive to find your wallpaper photos. Please grant the requested permission.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(onClick = onGrantPermissionClick, modifier = Modifier.fillMaxWidth()) {
        Text("Grant Permission")
    }

    OutlinedButton(onClick = onRevokeAndRetryClick, modifier = Modifier.fillMaxWidth()) {
        Text("Reset Sign-In & Retry")
    }
}

// ── Signed In ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInContent(
    uiState: UiState,
    isEnabled: Boolean,
    intervalMinutes: Int,
    lastChanged: Long,
    folderName: String,
    blurHomePercent: Int,
    blurLockPercent: Int,
    landscapeOnly: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onChangeNow: () -> Unit,
    onRefreshPhotos: () -> Unit,
    onBrowseFolders: () -> Unit,
    onBlurHomeChanged: (Int) -> Unit,
    onBlurLockChanged: (Int) -> Unit,
    onLandscapeOnlyChanged: (Boolean) -> Unit
) {
    // Account info
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Signed in as",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.userEmail ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // Folder selection
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wallpaper folder",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Tap Browse to pick a different folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onBrowseFolders) {
                    Text("Browse")
                }
            }
        }
    }

    // Photo count
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Photos in '$folderName'",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading...")
                    }
                } else {
                    Text(
                        text = "${uiState.photoCount} photos",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            IconButton(onClick = onRefreshPhotos) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
    }

    // Landscape only toggle
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Landscape only",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (uiState.isClassifying) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.classificationProgress ?: "Classifying...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (landscapeOnly) {
                    Text(
                        text = "${uiState.landscapePhotoCount} landscape photos available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Use only landscape/nature photos as wallpaper",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = landscapeOnly,
                onCheckedChange = onLandscapeOnlyChanged,
                enabled = !uiState.isClassifying
            )
        }
    }

    // Enable toggle
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-change wallpaper",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (isEnabled) "Wallpaper will change automatically" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }

    // Interval picker
    if (isEnabled) {
        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = AppPreferences.INTERVAL_OPTIONS
            .firstOrNull { it.first == intervalMinutes }?.second ?: "Unknown"

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Change interval",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    TextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        AppPreferences.INTERVAL_OPTIONS.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onIntervalChanged(minutes)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Blur settings
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wallpaper blur",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Adjust how much blur to apply to the wallpaper",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Home screen blur
            Text(
                text = "Home screen: ${blurHomePercent}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = blurHomePercent.toFloat(),
                onValueChange = { onBlurHomeChanged(it.toInt()) },
                valueRange = 0f..100f,
                steps = 19, // 0, 5, 10, ... 100
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Lock screen blur
            Text(
                text = "Lock screen: ${blurLockPercent}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = blurLockPercent.toFloat(),
                onValueChange = { onBlurLockChanged(it.toInt()) },
                valueRange = 0f..100f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Last changed
    if (lastChanged > 0) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Last changed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        lastChanged,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // Change now button
    Button(
        onClick = onChangeNow,
        enabled = !uiState.isChangingWallpaper && !uiState.isClassifying &&
                (if (landscapeOnly) uiState.landscapePhotoCount > 0 else uiState.photoCount > 0),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (uiState.isChangingWallpaper) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Changing wallpaper...")
        } else {
            Text("Change Wallpaper Now")
        }
    }

    uiState.statusMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
