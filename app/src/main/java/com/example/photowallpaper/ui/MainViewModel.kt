package com.example.photowallpaper.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.photowallpaper.api.DriveFile
import com.example.photowallpaper.auth.GoogleAuthManager
import com.example.photowallpaper.classifier.ClassificationManager
import com.example.photowallpaper.preferences.AppPreferences
import com.example.photowallpaper.repository.PhotosRepository
import com.example.photowallpaper.util.applyBlur
import com.example.photowallpaper.worker.WallpaperWorker
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val isSignedIn: Boolean = false,
    val hasPermission: Boolean = false,
    val userEmail: String? = null,
    val photoCount: Int = 0,
    val matchingPhotoCount: Int = 0,
    val isLoading: Boolean = false,
    val isClassifying: Boolean = false,
    val classificationProgress: String? = null,
    val isChangingWallpaper: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val recoveryIntent: Intent? = null
)

/** Represents a level in the folder navigation stack */
data class FolderBreadcrumb(
    val id: String?,    // null = root
    val name: String
)

data class FolderPickerState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val folders: List<DriveFile> = emptyList(),
    val breadcrumbs: List<FolderBreadcrumb> = listOf(FolderBreadcrumb(null, "My Drive")),
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val authManager = GoogleAuthManager(context)
    private val repository = PhotosRepository(context)
    private val preferences = AppPreferences(context)
    private val classificationManager = ClassificationManager(repository, preferences)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Cached file list from the latest photo fetch, used to build the preview gallery. */
    private val _cachedFiles = MutableStateFlow<List<DriveFile>>(emptyList())

    private val _folderPickerState = MutableStateFlow(FolderPickerState())
    val folderPickerState: StateFlow<FolderPickerState> = _folderPickerState.asStateFlow()

    val isEnabled = preferences.isEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val intervalMinutes = preferences.intervalMinutes.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_INTERVAL
    )
    val lastChanged = preferences.lastChanged.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0L
    )
    val folderName = preferences.folderName.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_FOLDER_NAME
    )
    val folderId = preferences.folderId.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )
    val blurHomePercent = preferences.blurHomePercent.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val blurLockPercent = preferences.blurLockPercent.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val selectedFilterLabels = preferences.selectedFilterLabels.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet()
    )
    val photoLabelsMap = preferences.photoLabelsMap.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    /** All unique labels detected across all photos — built dynamically at runtime. */
    val availableFilterLabels: StateFlow<List<String>> = preferences.photoLabelsMap
        .map { labelsMap ->
            labelsMap.values
                .flatMap { it }
                .toSet()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** DriveFile objects that match the current label filters, capped for the preview gallery. */
    val matchingPhotos: StateFlow<List<DriveFile>> = combine(
        _cachedFiles,
        preferences.selectedFilterLabels,
        preferences.photoLabelsMap
    ) { files, selected, labelsMap ->
        if (selected.isEmpty()) emptyList()
        else {
            val matchingIds = labelsMap
                .filterValues { labels -> labels.any { it in selected } }
                .keys
            files.filter { it.id in matchingIds }.take(MAX_PREVIEW_THUMBNAILS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkSignInStatus()

        // Keep matching photo count in sync whenever labels or filters change
        viewModelScope.launch {
            combine(
                preferences.selectedFilterLabels,
                preferences.photoLabelsMap
            ) { selected, labelsMap ->
                if (selected.isEmpty()) 0
                else labelsMap.count { (_, labels) -> labels.any { it in selected } }
            }.collect { count ->
                _uiState.value = _uiState.value.copy(matchingPhotoCount = count)
            }
        }
    }

    fun checkSignInStatus() {
        val account = authManager.getSignedInAccount()
        val hasPermission = authManager.hasRequiredScopes()
        _uiState.value = _uiState.value.copy(
            isSignedIn = account != null,
            hasPermission = hasPermission,
            userEmail = account?.email
        )

        if (account != null && hasPermission) {
            loadPhotos()
        }
    }

    fun onSignInSuccess(email: String?) {
        val hasPermission = authManager.hasRequiredScopes()
        _uiState.value = _uiState.value.copy(
            isSignedIn = true,
            hasPermission = hasPermission,
            userEmail = email,
            error = null
        )
        if (hasPermission) {
            loadPhotos()
        }
    }

    fun onSignInFailed(error: String) {
        _uiState.value = _uiState.value.copy(
            isSignedIn = false,
            error = "Sign-in failed: $error"
        )
    }

    fun onRecoveryIntentHandled() {
        _uiState.value = _uiState.value.copy(recoveryIntent = null)
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            WallpaperWorker.cancel(context)
            preferences.setEnabled(false)
            _cachedFiles.value = emptyList()
            _uiState.value = UiState()
        }
    }

    fun revokeAccessAndRetry() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, statusMessage = "Revoking access...")
            try {
                authManager.getAccessToken()?.let {
                    authManager.invalidateToken(it)
                }
                authManager.revokeAccess()
                authManager.signOut()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Revoke failed", e)
            }
            WallpaperWorker.cancel(context)
            preferences.setEnabled(false)
            _uiState.value = UiState(statusMessage = "Access revoked. Please sign in again.")
        }
    }

    // ── Photo loading ────────────────────────────────────────────────

    fun loadPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Read directly from DataStore to avoid the StateFlow race where
                // initial defaults haven't been replaced yet after process restart.
                val id = preferences.folderId.first()
                val name = preferences.folderName.first()

                val photos = if (id != null) {
                    repository.fetchPhotosByFolderId(id)
                } else {
                    repository.fetchPhotos(name)
                }

                _cachedFiles.value = photos
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    photoCount = photos.size,
                    hasPermission = true,
                    statusMessage = "Found ${photos.size} photos in '$name'"
                )

                // Trigger classification in background
                classifyPhotosInBackground(photos)
            } catch (e: UserRecoverableAuthException) {
                Log.w("MainViewModel", "User intervention required for Drive access")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasPermission = false,
                    recoveryIntent = e.intent
                )
            } catch (e: IllegalStateException) {
                Log.e("MainViewModel", "Folder error", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load photos", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load photos: ${e.message}"
                )
            }
        }
    }

    fun changeWallpaperNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChangingWallpaper = true, error = null)
            try {
                val id = preferences.folderId.first()
                val name = preferences.folderName.first()
                val matchingIds = computeMatchingFileIds()

                val bitmap = repository.getRandomPhotoBitmapFiltered(
                    folderId = id,
                    folderName = if (id == null) name else null,
                    matchingFileIds = matchingIds
                )

                if (bitmap != null) {
                    val homeBlur = preferences.blurHomePercent.first()
                    val lockBlur = preferences.blurLockPercent.first()
                    setWallpaperWithBlur(bitmap, homeBlur, lockBlur)
                    preferences.setLastChanged(System.currentTimeMillis())
                    _uiState.value = _uiState.value.copy(
                        isChangingWallpaper = false,
                        statusMessage = "Wallpaper changed!"
                    )
                } else {
                    val selected = selectedFilterLabels.value
                    _uiState.value = _uiState.value.copy(
                        isChangingWallpaper = false,
                        error = if (selected.isNotEmpty()) "No photos matching your filters" else "No photos available in folder"
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to change wallpaper", e)
                _uiState.value = _uiState.value.copy(
                    isChangingWallpaper = false,
                    error = "Failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Compute the set of file IDs that match the user's selected filter labels.
     * Returns empty set if no filters are active (meaning "all photos").
     * Uses suspend .first() to read directly from DataStore, avoiding the
     * StateFlow race where the initial empty default hasn't been replaced yet.
     */
    private suspend fun computeMatchingFileIds(): Set<String> {
        val selected = preferences.selectedFilterLabels.first()
        if (selected.isEmpty()) return emptySet()
        val labelsMap = preferences.photoLabelsMap.first()
        return labelsMap.filterValues { labels -> labels.any { it in selected } }.keys
    }

    // ── Settings ─────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnabled(enabled)
            if (enabled) {
                val interval = intervalMinutes.value
                WallpaperWorker.schedule(context, interval)
            } else {
                WallpaperWorker.cancel(context)
            }
        }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            preferences.setInterval(minutes)
            if (isEnabled.value) {
                WallpaperWorker.schedule(context, minutes)
            }
        }
    }

    fun setBlurHome(percent: Int) {
        viewModelScope.launch { preferences.setBlurHomePercent(percent) }
    }

    fun setBlurLock(percent: Int) {
        viewModelScope.launch { preferences.setBlurLockPercent(percent) }
    }

    // ── Label filters ────────────────────────────────────────────────

    fun addFilterLabel(label: String) {
        viewModelScope.launch {
            val current = selectedFilterLabels.value.toMutableSet()
            current.add(label)
            preferences.setSelectedFilterLabels(current)
        }
    }

    fun removeFilterLabel(label: String) {
        viewModelScope.launch {
            val current = selectedFilterLabels.value.toMutableSet()
            current.remove(label)
            preferences.setSelectedFilterLabels(current)
        }
    }

    fun clearAllFilters() {
        viewModelScope.launch {
            preferences.setSelectedFilterLabels(emptySet())
        }
    }

    private fun classifyPhotosInBackground(photos: List<DriveFile>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClassifying = true)
            try {
                val labelsMap = classificationManager.classifyPhotos(photos) { progress ->
                    if (!progress.isComplete) {
                        _uiState.value = _uiState.value.copy(
                            classificationProgress = "Classifying photos... ${progress.classified}/${progress.total}"
                        )
                    }
                }
                // Recompute matching count
                val selected = selectedFilterLabels.value
                val matchCount = if (selected.isEmpty()) 0
                else labelsMap.count { (_, labels) -> labels.any { it in selected } }

                _uiState.value = _uiState.value.copy(
                    isClassifying = false,
                    classificationProgress = null,
                    matchingPhotoCount = matchCount
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Classification failed", e)
                _uiState.value = _uiState.value.copy(
                    isClassifying = false,
                    classificationProgress = null
                )
            }
        }
    }

    // ── Wallpaper Blur Helper ────────────────────────────────────────

    companion object {
        const val MAX_PREVIEW_THUMBNAILS = 24

        fun setWallpaperWithBlur(
            context: android.content.Context,
            bitmap: Bitmap,
            homeBlurPercent: Int,
            lockBlurPercent: Int
        ) {
            val wallpaperManager = WallpaperManager.getInstance(context)

            if (homeBlurPercent == lockBlurPercent) {
                // Same blur for both — single call
                val blurred = applyBlur(bitmap, homeBlurPercent)
                wallpaperManager.setBitmap(
                    blurred, null, true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } else {
                // Different blur — set each screen separately
                val homeBlurred = applyBlur(bitmap, homeBlurPercent)
                wallpaperManager.setBitmap(
                    homeBlurred, null, true,
                    WallpaperManager.FLAG_SYSTEM
                )

                val lockBlurred = applyBlur(bitmap, lockBlurPercent)
                wallpaperManager.setBitmap(
                    lockBlurred, null, true,
                    WallpaperManager.FLAG_LOCK
                )
            }
        }
    }

    private fun setWallpaperWithBlur(bitmap: Bitmap, homeBlurPercent: Int, lockBlurPercent: Int) {
        Companion.setWallpaperWithBlur(context, bitmap, homeBlurPercent, lockBlurPercent)
    }

    // ── Folder Picker ────────────────────────────────────────────────

    fun openFolderPicker() {
        _folderPickerState.value = FolderPickerState(isOpen = true, isLoading = true)
        loadFoldersAt(parentId = null)
    }

    fun closeFolderPicker() {
        _folderPickerState.value = FolderPickerState()
    }

    /** Navigate into a subfolder */
    fun navigateToFolder(folder: DriveFile) {
        val current = _folderPickerState.value
        val newBreadcrumbs = current.breadcrumbs + FolderBreadcrumb(
            id = folder.id,
            name = folder.name ?: "Unnamed"
        )
        _folderPickerState.value = current.copy(
            isLoading = true,
            breadcrumbs = newBreadcrumbs,
            folders = emptyList(),
            error = null
        )
        loadFoldersAt(parentId = folder.id)
    }

    /** Navigate to a breadcrumb level */
    fun navigateToBreadcrumb(index: Int) {
        val current = _folderPickerState.value
        if (index >= current.breadcrumbs.size) return

        val newBreadcrumbs = current.breadcrumbs.take(index + 1)
        val targetId = newBreadcrumbs.last().id

        _folderPickerState.value = current.copy(
            isLoading = true,
            breadcrumbs = newBreadcrumbs,
            folders = emptyList(),
            error = null
        )
        loadFoldersAt(parentId = targetId)
    }

    /** Select the current folder (the one we're browsing inside) */
    fun selectCurrentFolder() {
        val current = _folderPickerState.value
        val lastBreadcrumb = current.breadcrumbs.last()

        viewModelScope.launch {
            if (lastBreadcrumb.id != null) {
                preferences.setFolder(lastBreadcrumb.id, lastBreadcrumb.name)
            } else {
                // Root selected — store name only, clear ID
                preferences.setFolderName("My Drive")
                preferences.setFolderId(null)
            }
            preferences.clearClassificationCache()
            repository.clearCache()
            _folderPickerState.value = FolderPickerState() // close
            loadPhotos()
        }
    }

    /** Select a specific folder from the list (without navigating into it) */
    fun selectFolder(folder: DriveFile) {
        viewModelScope.launch {
            preferences.setFolder(folder.id, folder.name ?: "Unnamed")
            preferences.clearClassificationCache()
            repository.clearCache()
            _folderPickerState.value = FolderPickerState() // close
            loadPhotos()
        }
    }

    private fun loadFoldersAt(parentId: String?) {
        viewModelScope.launch {
            try {
                val folders = repository.listFolders(parentId)
                _folderPickerState.value = _folderPickerState.value.copy(
                    isLoading = false,
                    folders = folders,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to list folders", e)
                _folderPickerState.value = _folderPickerState.value.copy(
                    isLoading = false,
                    error = "Failed to load folders: ${e.message}"
                )
            }
        }
    }
}
