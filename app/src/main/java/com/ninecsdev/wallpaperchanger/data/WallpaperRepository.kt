package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.AppPreferences
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationTrigger
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.TimerInterval
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperConfig
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Coordinator of the Data Layer.
 * Orchestrates Room Database, System States, and the Exhaustive Rotation Engine.
 */
object WallpaperRepository {
    private const val TAG = "WallpaperRepository"

    private  lateinit var appContext: Context
    private lateinit var dao: WallpaperDao

    private val _serviceEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serviceEvent: SharedFlow<Unit> = _serviceEvent.asSharedFlow()

    private val _configFlow = MutableStateFlow(WallpaperConfig())
    val configFlow: StateFlow<WallpaperConfig> = _configFlow.asStateFlow()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            dao = AppDatabase.getDatabase(appContext).wallpaperDao()
            _configFlow.value = getWallpaperConfig()
        }
    }

    /**
     * Emits a signal to all UI consumers that service state has changed.
     * Replaces broadcast-based UI sync for MainActivity and TileService.
     */
    fun notifyServiceStateChanged() {
        _serviceEvent.tryEmit(Unit)
    }

    // UI Data Access (Flows)
    fun getAllCollections(): Flow<List<WallpaperCollection>> {
        return dao.getAllCollections()
    }

    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> {
        return dao.getImagesForCollection(collectionId)
    }

    // Collection Management

    suspend fun importFolderAsCollection(name: String, treeUri: Uri, rule: CropRule) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.FOLDER,
                    rootUri = treeUri,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            val images = getImageListFromFolder(treeUri).map {
                it.copy(collectionId = collectionId)
            }
            dao.insertImages(images)
            Log.d(TAG, "Imported ${images.size} images to collection: $name")
        }
    }

    suspend fun createManualCollection(name: String, uris: List<Uri>, rule: CropRule) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.MANUAL,
                    rootUri = null,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            val images = uris.map {
                WallpaperImage(collectionId = collectionId, uri = it)
            }

            dao.insertImages(images)
        }
    }

    /**
     *  If the collection is a folder type it auto syncs
     */
    suspend fun setActiveCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.setActiveCollection(collectionId)
            val collection = dao.getCollectionById(collectionId)
            if (collection?.type == CollectionType.FOLDER) {
                Log.d(TAG, "Auto-syncing folder collection: ${collection.name}")
                syncCollection(collectionId)
            }
            clearMagazine()
            loadMagazine()
            refillDiskBuffer()
        }
    }

    /**
     * Non-flow version of getActiveCollection() for use in background tasks
     * like the CacheManager or Service.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? {
        return dao.getActiveCollection()
    }

    /**
     * Non-flow version of getImagesForCollection for background tasks.
     */
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage> {
        return dao.getImagesForCollectionOnce(collectionId)
    }

    /**
     *  Return the size of a collection in a non-flow way.
     */
    suspend fun getSizeOfCollection(collectionId: Long): Int {
        return dao.getImageCountOfCollection(collectionId)
    }

    suspend fun updateCollection(id: Long, newName: String, newRule: CropRule) {
        dao.updateCollection(id, newName, newRule)
    }

    suspend fun deleteCollection(collection: WallpaperCollection) {
        withContext(Dispatchers.IO) {
            if (collection.isActive) {
                clearMagazine()
                AppPreferences.setServiceRunning(appContext, false)
            }

            // Clean up internal files for all images in the collection
            val images = dao.getImagesForCollectionOnce(collection.id)
            images.forEach { image ->
                // Delete internalized source files (manual collections)
                if (collection.type == CollectionType.MANUAL) {
                    ImageInternalizer.deleteInternalFile(image.uri.path)
                }
                // Delete edited file if one exists
                ImageInternalizer.deleteInternalFile(image.editedUri?.path)
            }

            dao.deleteCollection(collection)
        }
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones, preserves manually added images.
     */
    suspend fun syncCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext

            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                    val freshImages = getImageListFromFolder(collection.rootUri).map {
                        it.copy(collectionId = collectionId)
                    }

                    val added = dao.syncFolderImages(collectionId, freshImages)
                    Log.d(TAG, "Sync complete: ${freshImages.size} on disk, $added new images added.")

                    if (collection.isActive) {
                        loadMagazine()
                        Log.i(TAG, "Active collection synced. Magazine reloaded.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed for collection ${collection.id}: ${e.message}")
                }
            }
        }
    }

    // Rotation Logic and vals
    // TODO: Move the rotation logic to a separate class in the future.
    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1

    /**
     * Loads and shuffles the images from the active collection into RAM.
     */
    suspend fun loadMagazine() {
        withContext(Dispatchers.IO) {
            val active = getActiveCollectionOnce() ?: return@withContext
            val images = getImagesForCollectionOnce(collectionId = active.id)

            synchronized(imageMagazine) {
                imageMagazine.clear()
                imageMagazine.addAll(images.shuffled())
                currentPointer = -1
            }
            Log.d(TAG, "Magazine loaded: ${imageMagazine.size} items.")
        }
    }

    /**
     * Processes the next wallpapers while only showing each once before shuffling.
     * It self-heals if an image fails to load.
     */
    suspend fun refillDiskBuffer(): Boolean {
        return withContext(Dispatchers.IO) {
            if (imageMagazine.isEmpty()) loadMagazine()

            val activeCollection = getActiveCollectionOnce() ?: return@withContext false

            val nextImage = synchronized(imageMagazine) {
                if (imageMagazine.isEmpty()) return@withContext false

                currentPointer++

                if (currentPointer >= imageMagazine.size) {
                    Log.d(TAG, "Cycle complete. Reshuffling for new sequence.")
                    imageMagazine.shuffle()
                    currentPointer = 0
                }

                imageMagazine[currentPointer]
            }

            val success = BufferManager.prepareNextWallpaper(nextImage,activeCollection.defaultCropRule )

            if (!success) {
                Log.w(TAG, "Failed to load ${nextImage.uri}. Removing from rotation.")
                ImageInternalizer.deleteInternalFile(nextImage.editedUri?.path)
                dao.deleteImageById(nextImage.id)
                synchronized(imageMagazine) { imageMagazine.remove(nextImage) }
                return@withContext refillDiskBuffer()
            }
            true
        }
    }

    /**
     * Helper to clear RAM when service stops
     */
    fun clearMagazine() {
        synchronized(imageMagazine) {
            imageMagazine.clear()
            currentPointer = -1
        }
    }

    // Service status & Preferences

    private var isStartingUp = false

    fun setStartingUp(loading: Boolean) {
        isStartingUp = loading
    }

    private var isPausedBySystem = false

    fun setServicePaused(paused: Boolean) {
        isPausedBySystem = paused
    }

    suspend fun getServiceState(): ServiceState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val isRunning = AppPreferences.isServiceRunning(appContext)
        val activeCollection = getActiveCollectionOnce()

        return when {
            activeCollection == null -> ServiceState.DisabledNoCollection
            isStartingUp -> ServiceState.Loading
            isRunning && isPausedBySystem -> ServiceState.Paused
            isRunning && WallpaperService.isAlive -> ServiceState.Running
            isRunning -> ServiceState.Stopped // Service died (e.g. killed by OEM, reboot failed)
            isPowerSave -> ServiceState.DisabledPowerSave
            else -> ServiceState.Stopped
        }
    }

    fun getWallpaperConfig(): WallpaperConfig {
        return WallpaperConfig(
            defaultWallpaperUri = AppPreferences.getDefaultWallpaperUri(appContext),
            revertToDefaultOnStop = AppPreferences.shouldRevertToDefault(appContext),
            rotationTrigger = AppPreferences.getRotationTrigger(appContext),
            timerInterval = AppPreferences.getTimerInterval(appContext),
            followFocusMode = AppPreferences.shouldFollowFocusMode(appContext)
        )
    }

    // Passthroughs to Preferences
    fun setRevertToDefault(revert: Boolean) {
        AppPreferences.setRevertToDefault(appContext, revert)
        _configFlow.value = getWallpaperConfig()
    }
    fun saveDefaultWallpaperUri(uri: Uri) {
        AppPreferences.saveDefaultWallpaperUri(appContext, uri)
        _configFlow.value = getWallpaperConfig()
    }
    fun setServiceRunning(isRunning: Boolean) = AppPreferences.setServiceRunning(appContext, isRunning)
    fun isServiceRunning(): Boolean { return AppPreferences.isServiceRunning(appContext) }
    fun shouldStartOnBoot(): Boolean = AppPreferences.shouldStartOnBoot(appContext)
    fun setStartOnBoot(enabled: Boolean) = AppPreferences.setStartOnBoot(appContext, enabled)
    fun setRotationTrigger(trigger: RotationTrigger) {
        AppPreferences.setRotationTrigger(appContext, trigger)
        _configFlow.value = getWallpaperConfig()
    }
    fun setTimerInterval(interval: TimerInterval) {
        AppPreferences.setTimerInterval(appContext, interval)
        _configFlow.value = getWallpaperConfig()
    }
    fun setFollowFocusMode(enabled: Boolean) {
        AppPreferences.setFollowFocusMode(appContext, enabled)
        _configFlow.value = getWallpaperConfig()
    }

    // File System Utilities
    // TODO: Move the file system utility to a separate class in the future.

    /**
     * Scans the user-selected folder for images.
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return A list of [WallpaperImage] objects.
     */
    private suspend fun getImageListFromFolder(rootFolderUri: Uri): List<WallpaperImage> {
        return withContext(Dispatchers.IO) {
            val imageList = mutableListOf<WallpaperImage>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) // Get the column index

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)

                        if (mimeType != null && mimeType.startsWith("image/")) {
                            val docId = cursor.getString(idCol)
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                            imageList.add(WallpaperImage(collectionId = 0, uri = docUri))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder scan failed: $e")
            }
            Log.d(TAG, "Folder scan found ${imageList.size} valid images.")
            imageList
        }
    }
}
