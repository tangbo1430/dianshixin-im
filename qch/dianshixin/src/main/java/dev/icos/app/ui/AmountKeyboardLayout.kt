package dev.icos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AmountKeyboardLayout(
    settings: RecordEfficiencySettings,
    confirmButton: (@Composable () -> Unit)?,
    confirmBelow: Boolean,
    keyButton: @Composable (String, Modifier) -> Unit,
) {
    if (!confirmBelow) {
        LedgerAmountKeyboardLayout(settings, confirmButton, keyButton)
        return
    }
    val deleteFirst = settings.deleteKeyPosition == DeleteKeyPosition.LEFT
    val decimalFirst = settings.decimalKeyPosition == DecimalKeyPosition.BEFORE_ZERO
    val middleKey = if (decimalFirst) "." else "0"
    val bottomKey = if (decimalFirst) "0" else "."
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (deleteFirst) keyButton("⌫", Modifier.weight(1f))
            (1..3).forEach { keyButton("$it", Modifier.weight(1f)) }
            if (!deleteFirst) keyButton("⌫", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (4..6).forEach { keyButton("$it", Modifier.weight(1f)) }
            keyButton(middleKey, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (7..9).forEach { keyButton("$it", Modifier.weight(1f)) }
            keyButton(bottomKey, Modifier.weight(1f))
        }
        if (confirmButton != null) {
            Box(Modifier.fillMaxWidth().height(56.dp)) { confirmButton() }
        }
    }
}

@Composable
private fun LedgerAmountKeyboardLayout(
    settings: RecordEfficiencySettings,
    confirmButton: (@Composable () -> Unit)?,
    keyButton: @Composable (String, Modifier) -> Unit,
) {
    val decimalFirst = settings.decimalKeyPosition == DecimalKeyPosition.BEFORE_ZERO
    val deleteFirst = settings.deleteKeyPosition == DeleteKeyPosition.LEFT
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (confirmButton != null && deleteFirst) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keyButton("⌫", Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(152.dp)) { confirmButton() }
            }
        }
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (2 downTo 0).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..3).forEach { column ->
                        keyButton("${row * 3 + column}", Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (confirmButton == null && deleteFirst) keyButton("⌫", Modifier.weight(1f))
                listOf(if (decimalFirst) "." else "0", if (decimalFirst) "0" else ".").forEach { key ->
                    keyButton(
                        key,
                        Modifier.weight(if (confirmButton != null && key == "0") 2f else 1f),
                    )
                }
                if (confirmButton == null && !deleteFirst) keyButton("⌫", Modifier.weight(1f))
            }
        }
        if (confirmButton != null && !deleteFirst) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keyButton("⌫", Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(152.dp)) { confirmButton() }
            }
        }
    }
}
