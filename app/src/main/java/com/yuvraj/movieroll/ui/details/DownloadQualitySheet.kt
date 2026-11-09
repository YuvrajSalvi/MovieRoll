package com.yuvraj.movieroll.ui.details

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.yuvraj.movieroll.core.model.EpisodeItem
import com.yuvraj.movieroll.core.model.MediaDetail
import com.yuvraj.movieroll.ui.details.downloads.DownloadSourcesSheet

/**
 * Public entry point for DownloadQualitySheet, seamlessly delegating to the redesigned editorial DownloadSourcesSheet.
 * Preserves the exact signature, parameters, and invocation contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(
    detail: MediaDetail,
    episode: EpisodeItem? = null,
    provider: String? = null,
    onDismiss: () -> Unit,
    onDownloadStarted: (message: String) -> Unit
) {
    DownloadSourcesSheet(
        detail = detail,
        episode = episode,
        provider = provider,
        onDismiss = onDismiss,
        onDownloadStarted = onDownloadStarted
    )
}
