package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Collection List screen.
 * Owns [CollectionUiState] and handles imports, edits, and preview loading.
 */
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WallpaperRepository
    private val context = application.applicationContext

    // ── Internal mutable state slices ────────────────────────────────────

    private var pendingFolderUri: Uri? = null
    private var pendingPhotosUris: List<Uri> = emptyList()

    /** Preview thumbnails loaded on-demand for visible grid items. */
    private val _previewStates = MutableStateFlow<Map<Long, CollectionPreviewState>>(emptyMap())

    /** Modal / processing state managed by this screen. */
    private val _screenState = MutableStateFlow(ScreenModalState())

    /** Combined public state built reactively. */
    val uiState: StateFlow<CollectionUiState> = combine(
        repository.getAllCollections(),
        _previewStates,
        _screenState
    ) { collections, previews, modal ->
        CollectionUiState(
            allCollections = collections,
            previewStates = previews,
            isPickerMode = modal.isPickerMode,
            isShowingCreateModal = modal.isShowingCreateModal,
            editingCollection = modal.editingCollection,
            isProcessing = modal.isProcessing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionUiState()
    )

    // ── Pending source selection (folder / photos) ───────────────────────

    fun setPendingFolderUri(uri: Uri) {
        pendingFolderUri = uri
        pendingPhotosUris = emptyList()
    }

    fun setPendingPhotos(uris: List<Uri>) {
        pendingPhotosUris = uris
        pendingFolderUri = null
    }

    fun hasPendingFolder(): Boolean = pendingFolderUri != null
    fun hasPendingPhotos(): Boolean = pendingPhotosUris.isNotEmpty()

    // ── Collection CRUD ──────────────────────────────────────────────────

    fun finalizeFolderCollection(name: String, rule: CropRule, onComplete: () -> Unit) {
        val uri = pendingFolderUri ?: return
        viewModelScope.launch {
            setProcessing(true)
            repository.importFolderAsCollection(name, uri, rule)
            pendingFolderUri = null
            setProcessing(false)
            onComplete()
        }
    }

    fun finalizeManualCollection(name: String, rule: CropRule, onComplete: () -> Unit) {
        if (pendingPhotosUris.isEmpty()) return
        viewModelScope.launch {
            setProcessing(true)
            val internalizedUris = ImageInternalizer.internalizeImages(context, pendingPhotosUris)
            repository.createManualCollection(name, internalizedUris, rule)
            pendingPhotosUris = emptyList()
            setProcessing(false)
            onComplete()
        }
    }

    fun deleteCollection(collection: WallpaperCollection, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.deleteCollection(collection)
            onComplete()
        }
    }

    fun updateCollection(collectionId: Long, newName: String, cropRule: CropRule) {
        viewModelScope.launch {
            repository.updateCollection(collectionId, newName, cropRule)
        }
    }

    fun syncCollection(collectionId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            setProcessing(true)
            repository.syncCollection(collectionId)
            setProcessing(false)
            onComplete()
        }
    }

    // ── Preview loading (Fix #1 — no @Composable) ───────────────────────

    /**
     * Loads the 2×2 thumbnail previews for a collection.
     * Called once per visible grid item; cached in the map so it
     * doesn't re-fetch unless the screen is recreated.
     */
    fun loadPreview(collectionId: Long) {
        if (_previewStates.value.containsKey(collectionId)) return
        viewModelScope.launch {
            val uris = repository.getImagesForCollectionOnce(collectionId)
                .take(4)
                .map { it.uri }
            val size = repository.getSizeOfCollection(collectionId)
            _previewStates.update { current ->
                current + (collectionId to CollectionPreviewState(uris, size))
            }
        }
    }

    // ── Modal / navigation helpers ───────────────────────────────────────

    fun setPickerMode(picker: Boolean) {
        _screenState.update { it.copy(isPickerMode = picker) }
    }

    fun toggleCreateModal(show: Boolean) {
        _screenState.update { it.copy(isShowingCreateModal = show) }
    }

    fun openEditModal(collection: WallpaperCollection) {
        _screenState.update { it.copy(editingCollection = collection) }
    }

    fun closeEditModal() {
        _screenState.update { it.copy(editingCollection = null) }
    }

    private fun setProcessing(loading: Boolean) {
        _screenState.update { it.copy(isProcessing = loading) }
    }
}

/** Internal holder so modal flags can be combined as a single flow. */
private data class ScreenModalState(
    val isPickerMode: Boolean = false,
    val isShowingCreateModal: Boolean = false,
    val editingCollection: WallpaperCollection? = null,
    val isProcessing: Boolean = false
)