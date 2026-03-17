package com.ninecsdev.wallpaperchanger.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionListScreen
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CreateListCard
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.EditCollectionCard
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainScreen
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val collectionViewModel: CollectionViewModel by viewModels()

    // Activity Result Launchers

    // Notification permission
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startWallpaperService()
        }
    }

    // Folder picker
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            collectionViewModel.setPendingFolderUri(it)
            collectionViewModel.toggleCreateModal(true)
        }
    }

    // Photos picker
    private val photosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            collectionViewModel.setPendingPhotos(uris)
            collectionViewModel.toggleCreateModal(true)
        }
    }

    // Default wallpaper picker
    private val defaultWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { mainViewModel.internalizeAndSaveDefaultWallpaper(it) }
    }

    // Battery optimization exemption (Required for boot-start)
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startWallpaperService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val mainState by mainViewModel.uiState.collectAsState()
                val collectionState by collectionViewModel.uiState.collectAsState()

                CompositionLocalProvider(LocalContentColor provides NothingWhite) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // NAVIGATION LAYER
                        if (mainState.isShowingLists) {
                            CollectionListScreen(
                                uiState = collectionState,
                                onRequestPreview = { collectionViewModel.loadPreview(it) },
                                onCollectionClick = { id ->
                                    if (collectionState.isPickerMode) {
                                        mainViewModel.setActiveCollection(id)
                                        mainViewModel.setShowLists(false)
                                    } else {
                                        val collection = collectionState.allCollections.find { it.id == id }
                                        collection?.let { collectionViewModel.openEditModal(it) }
                                    }
                                },
                                onSortOrderChange = { collectionViewModel.setSortOrder(it) },
                                onAddClick = { collectionViewModel.toggleCreateModal(true) },
                                onBackClick = { mainViewModel.setShowLists(false) }
                            )
                        } else {
                            MainScreen(
                                uiState = mainState,
                                onSelectFolderClick = {
                                    collectionViewModel.setPickerMode(true)
                                    mainViewModel.setShowLists(true)
                                },
                                onOpenCollectionsClick = {
                                    collectionViewModel.setPickerMode(false)
                                    mainViewModel.setShowLists(true)
                                },
                                onSelectDefaultClick = {
                                    defaultWallpaperLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onToggleRevert = { mainViewModel.setRevertToDefault(it) },
                                onStartClick = { checkPermissionsAndStart() },
                                onStopClick = {
                                    val intent = Intent(
                                        this@MainActivity,
                                        WallpaperService::class.java
                                    ).apply {
                                        action = WallpaperService.ACTION_STOP_SERVICE
                                    }

                                    startService(intent)
                                    mainViewModel.refreshServiceState()
                                },
                                onDelaySelected = { mainViewModel.setDelayLabel(it) }
                            )
                        }

                        // POP-UPs
                        if (collectionState.isShowingCreateModal) {
                            CreateListCard(
                                isProcessing = collectionState.isProcessing,
                                hasPendingFolder = collectionViewModel.hasPendingFolder(),
                                hasPendingPhotos = collectionViewModel.hasPendingPhotos(),
                                onDismiss = { collectionViewModel.toggleCreateModal(false) },
                                onFolderSelect = {
                                    collectionViewModel.toggleCreateModal(false)
                                    folderLauncher.launch(null)
                                },
                                onPhotosSelect = {
                                    collectionViewModel.toggleCreateModal(false)
                                    photosLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onCreateClick = { name, rule, frequency ->
                                    if (collectionViewModel.hasPendingFolder()) {
                                        collectionViewModel.finalizeFolderCollection(name, rule, frequency) {
                                            collectionViewModel.toggleCreateModal(false)
                                            if (collectionState.allCollections.isEmpty()) checkPermissionsAndStart()
                                        }
                                    } else {
                                        collectionViewModel.finalizeManualCollection(name, rule, frequency) {
                                            collectionViewModel.toggleCreateModal(false)
                                            if (collectionState.allCollections.isEmpty()) checkPermissionsAndStart()
                                        }
                                    }
                                }
                            )
                        }

                        collectionState.editingCollection?.let { collection ->
                            EditCollectionCard(
                                collection = collection,
                                isProcessing = collectionState.isProcessing,
                                onDismiss = { collectionViewModel.closeEditModal() },
                                onEdit = { newName, newRule, newFrequency ->
                                    collectionViewModel.updateCollection(
                                        collection.id,
                                        newName,
                                        newRule,
                                        newFrequency
                                    )
                                },
                                onSetActive = { mainViewModel.setActiveCollection(collection.id) },
                                onDelete = {
                                    val wasActive = collection.isActive
                                    collectionViewModel.deleteCollection(collection) {
                                        collectionViewModel.closeEditModal()

                                        if (wasActive) {
                                            val intent = Intent(
                                                this@MainActivity,
                                                WallpaperService::class.java
                                            ).apply {
                                                action = WallpaperService.ACTION_STOP_SERVICE
                                            }
                                            startService(intent)
                                            mainViewModel.refreshServiceState()
                                        }
                                    }
                                },
                                onSyncClick = {
                                    collectionViewModel.syncCollection(collection.id) {}
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Checks battery optimization exemption before starting the service.
     * If not exempted, shows a system dialog first, then starts the service
     * regardless of the user's choice (it only affects boot-start behavior).
     */
    private fun startWallpaperService() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            batteryExemptionLauncher.launch(intent)
            return
        }

        val intent = Intent(this, WallpaperService::class.java)
        startForegroundService(intent)
        mainViewModel.refreshServiceState()
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshServiceState()
    }
}
