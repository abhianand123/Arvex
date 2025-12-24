package dev.abhi.arvex.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.abhi.arvex.LocalPlayerConnection
import dev.abhi.arvex.constants.DEFAULT_PLAYER_BACKGROUND
import dev.abhi.arvex.constants.DarkMode
import dev.abhi.arvex.constants.DarkModeKey
import dev.abhi.arvex.constants.PlayerBackgroundStyle
import dev.abhi.arvex.constants.PlayerBackgroundStyleKey
import dev.abhi.arvex.ui.component.BottomSheetState
import dev.abhi.arvex.ui.player.BottomSheetPlayer
import dev.abhi.arvex.ui.player.QueueScreen
import dev.abhi.arvex.utils.rememberEnumPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavController,
//    scrollBehavior: TopAppBarScrollBehavior,
    playerBottomSheetState: BottomSheetState,
    modifier: Modifier,
) {
    val playerConnection = LocalPlayerConnection.current
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )

    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    val onBackgroundColor = when (playerBackground) {
        PlayerBackgroundStyle.FOLLOW_THEME -> MaterialTheme.colorScheme.secondary
        else ->
            if (useDarkTheme)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onPrimary
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        QueueScreen(
            playerBottomSheetState = playerBottomSheetState,
            onTerminate = {
                playerConnection?.service?.queueBoard?.detachedHead = false
            },
            navController = navController
        )
        BottomSheetPlayer(
            state = playerBottomSheetState,
            navController = navController
        )

    }
}
