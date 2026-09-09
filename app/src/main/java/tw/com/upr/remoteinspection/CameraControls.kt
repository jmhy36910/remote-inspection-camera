package tw.com.upr.remoteinspection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ParameterControl(
    title: String, unit: String, value: Float, text: String, range: ClosedFloatingPointRange<Float>,
    auto: Boolean?, onAuto: (Boolean) -> Unit, onText: (String) -> Unit,
    onValue: (Float) -> Unit, onApply: () -> Unit, onFocus: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (auto != null) {
                    Text(if (auto) "自動" else "鎖定", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = auto, onCheckedChange = onAuto)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val movable = range.endInclusive > range.start
                Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue,
                    enabled = movable, valueRange = if (movable) range else range.start..(range.start + 1f),
                    modifier = Modifier.weight(1f))
                OutlinedTextField(text, onText, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onApply() }),
                    modifier = Modifier.width(104.dp).onFocusChanged { onFocus(it.isFocused) })
                Text(unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(26.dp))
            }
        }
    }
}

@Composable
fun ChoiceDialog(title: String, choices: List<String>, dismiss: () -> Unit, select: (Int) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            choices.forEachIndexed { index, label ->
                TextButton(onClick = { select(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("關閉") } })
}
