package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditorPage(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    error: String? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    topBarContent: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ThemeSystemBars()
        val imeVisible = WindowInsets.isImeVisible
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    if (topBarContent != null) {
                        Box(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) { topBarContent() }
                    } else {
                        TopAppBar(
                            title = title,
                            navigationIcon = { IconButton(onClick = onDismissRequest) { AppIcon(AppSymbol.BACK, "返回") } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    }
                    error?.let {
                        EditorErrorBanner(
                            it,
                            Modifier.padding(horizontal = 16.dp, vertical = if (imeVisible) 0.dp else 4.dp),
                            compact = imeVisible,
                        )
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!imeVisible && bottomContent != null) {
                            bottomContent()
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                dismissButton()
                                Spacer(Modifier.weight(1f))
                                confirmButton()
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = if (imeVisible) 4.dp else 12.dp)) { text() }
        }
    }
}
