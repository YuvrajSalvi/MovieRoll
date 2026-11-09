package com.yuvraj.movieroll.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yuvraj.movieroll.ui.settings.SettingsRootScreen

@Composable
fun ProfileScreen(
    onNavigateToPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsRootScreen(
        onNavigateToPage = onNavigateToPage,
        modifier = modifier
    )
}
