package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.RotationTrigger
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.TimerInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Main Dashboard screen.
 * Builds [MainUiState] reactively by combining three independent flows:
 *   1. Collections (Room) - active collection + previews
 *   2. Service events - service state changes
 *   3. Config flow - user preferences
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WallpaperRepository

    /**
     * Internal trigger that forces a re-computation of the service state.
     * Merged with the repository's serviceEvent so both external (service)
     * and internal (user tap) triggers feed the same pipeline.
     */
    private val _serviceRefresh = MutableStateFlow(0L)

    /** Lightweight navigation flag — only field the VM mutates directly. */
    private val _showLists = MutableStateFlow(false)

    val uiState: StateFlow<MainUiState> = combine(
        repository.getAllCollections(),
        repository.configFlow,
        repository.serviceEvent.onStart { emit(Unit) },
        _serviceRefresh,
        _showLists
    ) { collections, config, _, _, showLists ->

        val active = collections.find { it.isActive }

        // Self-healing: if SharedPrefs says "running" but service is dead, fix it
        val serviceState = repository.getServiceState().let { state ->
            if (repository.isServiceRunning() && state is ServiceState.Stopped) {
                repository.setServiceRunning(false)
                repository.getServiceState()
            } else {
                state
            }
        }

        val previews = if (active != null) {
            repository.getImagesForCollectionOnce(active.id)
        } else {
            emptyList()
        }

        MainUiState(
            serviceState = serviceState,
            activeCollection = active,
            previewImages = previews.take(3),
            activeCollectionSize = previews.size,
            config = config,
            isShowingLists = showLists
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    // Actions

    fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    fun setShowLists(show: Boolean) {
        _showLists.value = show
    }

    fun setRevertToDefault(isChecked: Boolean) {
        repository.setRevertToDefault(isChecked)
    }

    fun setRotationTrigger(trigger: RotationTrigger) {
        repository.setRotationTrigger(trigger)
    }

    fun setTimerInterval(interval: TimerInterval) {
        repository.setTimerInterval(interval)
    }

    fun setFollowFocusMode(enabled: Boolean) {
        repository.setFollowFocusMode(enabled)
    }

    fun internalizeAndSaveDefaultWallpaper(uri: Uri) {
        viewModelScope.launch {
            val previousUri = repository.getWallpaperConfig().defaultWallpaperUri
            if (previousUri != null) ImageInternalizer.deleteInternalFile(previousUri.path)
            val internalized = ImageInternalizer.internalizeImages(getApplication(), listOf(uri))
            internalized.firstOrNull()?.let { repository.saveDefaultWallpaperUri(it) }
        }
    }

    /**
     * Called by MainActivity when start/stop intents are fired
     * to provide instant visual feedback.
     * Also useful for onResume() to catch external state changes.
     */
    fun refreshServiceState() {
        _serviceRefresh.value = System.nanoTime()
    }
}
