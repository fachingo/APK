package ca.canadavhs.snapmobile

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable fun ResolutionSettings(prefs: SharedPreferences) {
    var selected by remember { mutableStateOf(PhotoResolution.read(prefs)) }
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Photo resolution")
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("${selected.label} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PhotoResolution.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    selected = option; prefs.edit().putString("photoResolution", option.name).apply(); expanded = false
                }) }
            }
        }
        Text("3 MP is the new default. Smaller sizes save faster but retain less tiny text and barcode detail. The camera selects the closest supported size, shown in the preview. Originals retain their camera JPEG and EXIF.", style = MaterialTheme.typography.bodySmall)
    }
}
