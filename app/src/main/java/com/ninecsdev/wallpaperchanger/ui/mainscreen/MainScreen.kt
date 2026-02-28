package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperConfig
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Stateful wrapper for the Main Dashboard.
 * Connects the ViewModel state to the Stateless [MainScreenContent].
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSelectFolder: () -> Unit,
    onOpenCollections: () -> Unit,
    onSelectDefault: () -> Unit,
    onStartRequest: () -> Unit,
    onStopRequest: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    MainScreenContent(
        uiState = uiState,
        onSelectFolderClick = onSelectFolder,
        onOpenCollectionsClick = onOpenCollections,
        onSelectDefaultClick = onSelectDefault,
        onToggleRevert = { viewModel.setRevertToDefault(it) },
        onStartClick = onStartRequest,
        onStopClick = onStopRequest
    )
}

/**
 * Stateless UI for the main screen.
 * Composed of the status header, collection selection card,
 * default wallpaper card, and service control buttons.
 */
@Composable
fun MainScreenContent(
    uiState: MainUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSelectFolderClick: () -> Unit,
    onOpenCollectionsClick: () -> Unit,
    onToggleRevert: (Boolean) -> Unit,
    onSelectDefaultClick: () -> Unit
) {
    Scaffold(
        containerColor = NothingBlack,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    ServiceStatusHeader(
                        state = uiState.serviceState,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onOpenCollectionsClick,
                        modifier = Modifier.offset(y = (-20).dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.icon_grid),
                            contentDescription = "Collections",
                            modifier = Modifier.size(32.dp),
                            tint = NothingWhite
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                WallpaperSelectionCard(
                    activeCollection = uiState.activeCollection,
                    previewImages = uiState.previewImages,
                    totalImages = uiState.activeCollectionSize,
                    onSelectFolderClick = onSelectFolderClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                DefaultWallpaperCard(
                    revertToDefault = uiState.config.revertToDefaultOnStop,
                    defaultUri = uiState.config.defaultWallpaperUri,
                    onToggleRevert = onToggleRevert,
                    onSelectDefaultClick = onSelectDefaultClick
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .statusBarsPadding()
            ) {
                ServiceControlButtons(
                    isStartEnabled = uiState.isStartEnabled,
                    isStopEnabled = uiState.isStopEnabled,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick
                )
            }
        }
    }
}

@Preview(showSystemUi = true, name = "Main Screen - Running", backgroundColor = 0xFF000000)
@Composable
fun MainScreenRunningPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalContentColor provides NothingWhite) {
            MainScreenContent(
                uiState = MainUiState(
                    serviceState = ServiceState.Running,
                    config = WallpaperConfig(revertToDefaultOnStop = true)
                ),
                onStartClick = {},
                onStopClick = {},
                onSelectFolderClick = {},
                onOpenCollectionsClick = {},
                onToggleRevert = {},
                onSelectDefaultClick = {}
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Main Screen - Setup Needed", backgroundColor = 0xFF000000)
@Composable
fun MainScreenEmptyPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalContentColor provides NothingWhite) {
            MainScreenContent(
                uiState = MainUiState(
                    serviceState = ServiceState.DisabledNoCollection,
                    activeCollection = null
                ),
                onStartClick = {},
                onStopClick = {},
                onSelectFolderClick = {},
                onOpenCollectionsClick = {},
                onToggleRevert = {},
                onSelectDefaultClick = {}
            )
        }
    }
}
