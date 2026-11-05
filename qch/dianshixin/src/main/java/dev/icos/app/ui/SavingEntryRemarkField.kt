package dev.icos.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun SavingEntryRemarkField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    error: String?,
    count: Int,
) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    // Re-scroll the whole field after the IME or error banner changes the viewport.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, imeBottom, error) {
        if (!focused) return@LaunchedEffect
        withFrameNanos {}
        requester.bringIntoView()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("备注") },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = { Text(error ?: "$count / 1000") },
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .onFocusChanged { focused = it.isFocused },
    )
}
