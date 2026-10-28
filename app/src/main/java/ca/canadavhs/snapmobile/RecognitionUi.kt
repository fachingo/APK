package ca.canadavhs.snapmobile

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

fun recognitionLabel(mode: String): String = when (mode) {
    "CLASSICAL" -> "Model-free checks"; "OCR" -> "Offline OCR"; "OPENAI" -> "OpenAI"; else -> "Off"
}

@Composable fun RecognitionSettings(vm: CaptureViewModel) {
    val repo = vm.repo; val prefs = repo.preferences; val context = LocalContext.current
    var mode by remember { mutableStateOf(repo.recognitionMode()) }
    var keyDialog by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(SecureApiKeyStore(context).hasKey()) }
    var model by remember { mutableStateOf(prefs.getString("openaiModel", DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL) }
    var models: List<String> by remember { mutableStateOf<List<String>>(prefs.getStringSet("openaiModels", emptySet<String>())?.sorted() ?: emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }; var modelPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recognition", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RECOGNITION_MODES.forEach { option -> FilterChip(selected = mode == option, label = { Text(recognitionLabel(option)) }, onClick = {
                vm.action { repo.setRecognitionMode(option); mode = option }
            }) }
        }
        Text(when (mode) {
            "CLASSICAL" -> "ZXing decodes product barcodes. Classical pixel checks flag possible blur, low light and glare. No trained models, OCR or network calls; this mode cannot infer a movie title."
            "OCR" -> "Tesseract 5.5.1 transcribes visible English text entirely offline, with ZXing barcodes and image checks. It uses bundled trained character data, not a generative AI model. Select and verify the printed title in Batches."
            "OPENAI" -> "When you tap Recognize in Batches, up to three downsized photos are sent to OpenAI using your key. API usage is charged to your account. Originals are unchanged, and every result needs review."
            else -> "Capture, grouping, export and offline ZXing barcode scanning stay available. No recognition job or external request runs automatically."
        }, style = MaterialTheme.typography.bodySmall)
        Text("Recognition runs from Batches after capture; switching modes cancels unfinished jobs from the previous mode.", style = MaterialTheme.typography.bodySmall)
        if (mode == "OPENAI") {
            Text(if (hasKey) "API key saved with Android Keystore encryption" else "No OpenAI API key saved", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { keyDialog = true }) { Text(if (hasKey) "Replace API key" else "Add API key") }
                if (hasKey) TextButton(onClick = { vm.action { withContext(Dispatchers.IO) { SecureApiKeyStore(context).remove() }; repo.setRecognitionMode("OFF"); hasKey = false; mode = "OFF" } }) { Text("Remove key") }
            }
            OutlinedTextField(model, { value -> if (value.startsWith("sk-", ignoreCase = true)) { model = ""; vm.message = "Use Add API key for your secret. The model field only accepts model IDs." } else { model = value.take(160); if (validOpenAiModelId(model)) prefs.edit().putString("openaiModel", model).apply() } }, label = { Text("OpenAI model ID") }, singleLine = true,
                supportingText = { Text("Default: gpt-4.1-mini. Requires image input, Responses and structured output.") })
            Row {
                TextButton(enabled = hasKey && !loading, onClick = {
                    loading = true
                    vm.action {
                        try {
                            val key = withContext(Dispatchers.IO) { SecureApiKeyStore(context).read() } ?: error("Save an API key first.")
                            val available = OpenAiRecognitionClient().models(key)
                            models = available; prefs.edit().putStringSet("openaiModels", available.toSet()).apply()
                            vm.message = "Key accepted; ${available.size} candidate models loaded. Image support is checked when recognition runs."
                        } finally { loading = false }
                    }
                }) { Text(if (loading) "Loading…" else "Test key / refresh models") }
                if (models.isNotEmpty()) TextButton(onClick = { modelPicker = true }) { Text("Choose model") }
            }
        }
    }
    if (keyDialog) SecureKeyDialog(vm, onSaved = { hasKey = true; keyDialog = false }, dismiss = { keyDialog = false })
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text("Available model IDs") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("The Models API does not report image capabilities. Choose a vision model; incompatible requests produce a clear error.", style = MaterialTheme.typography.bodySmall)
            models.forEach { id -> TextButton(onClick = { model = id; prefs.edit().putString("openaiModel", id).apply(); modelPicker = false }) { Text(id) } }
        }
    }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text("Done") } })
}

@Composable private fun SecureKeyDialog(vm: CaptureViewModel, onSaved: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }; var saving by remember { mutableStateOf(false) }
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = { if (!saving) { input = ""; dismiss() } }, title = { Text("OpenAI API key") }, text = { Column {
        Text("Stored encrypted on this device. It is never included in exports or source code. Enter an API key, not a ChatGPT password.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(input, { input = it.take(512) }, label = { Text("Paste API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    } }, confirmButton = { TextButton(enabled = !saving && input.trim().length >= 16, onClick = {
        saving = true
        vm.action { try { withContext(Dispatchers.IO) { SecureApiKeyStore(context).save(input) }; input = ""; onSaved() } finally { saving = false } }
    }) { Text("Save securely") } }, dismissButton = { TextButton(enabled = !saving, onClick = { input = ""; dismiss() }) { Text("Cancel") } })
}

@Composable fun RecognitionDialog(vm: CaptureViewModel, group: GroupRow, rows: List<RecognitionRow>, dismiss: () -> Unit) {
    val repo = vm.repo; val context = LocalContext.current; val latest = rows.firstOrNull()
    val allPhotos by vm.photos.collectAsStateWithLifecycle()
    val fingerprint = photoFingerprint(allPhotos.filter { it.groupId == group.id && !it.deleted && it.state == "READY" }.sortedBy { it.position })
    val current = latest?.inputFingerprint == fingerprint
    val result = remember(latest?.resultJson) { runCatching { JSONObject(latest?.resultJson ?: "") }.getOrNull() }
    var title by remember(latest?.id, latest?.resultJson) { mutableStateOf(latest?.acceptedTitle ?: result?.optString("title")?.takeUnless { it == "UNKNOWN" } ?: "") }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Product %03d · recognition".format(group.number)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mode: ${recognitionLabel(repo.recognitionMode())}")
            if (repo.recognitionMode() == "OPENAI") Text("Run sends up to 3 downsized photos to OpenAI; API charges apply.", style = MaterialTheme.typography.bodySmall)
            if (latest != null) {
                Text("${latest.state} · ${latest.model}", style = MaterialTheme.typography.bodySmall)
                if (latest.state == "QUEUED" && latest.provider == "OPENAI") Text("Waits for an internet connection. Capture still works offline.", style = MaterialTheme.typography.bodySmall)
                if (latest.state == "SUCCEEDED" && !current) Text("Photos changed since this result. Run recognition again before confirming.", color = MaterialTheme.colorScheme.error)
                if (latest.error.isNotEmpty()) Text(latest.error, color = MaterialTheme.colorScheme.error)
                if (result != null) {
                    val meta = listOf("edition", "distributor", "year").mapNotNull { result.optString(it).takeUnless { v -> v.isBlank() || v == "null" } }.joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                    result.optJSONArray("barcode_candidates")?.let { values ->
                        if (values.length() > 0) Text("Barcode candidates · verify before use", style = MaterialTheme.typography.labelMedium)
                        for (i in 0 until minOf(values.length(), 12)) {
                            val candidate = values.optJSONObject(i)
                            Text(if (candidate == null) values.optString(i) else "${candidate.optString("raw")} · ${candidate.optString("format")} · ${if (candidate.optBoolean("valid")) "checksum valid" else "verify checksum"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    result.optJSONArray("uncertainty_reasons")?.let { values -> for (i in 0 until minOf(values.length(), 5)) Text(values.optString(i), style = MaterialTheme.typography.bodySmall) }
                    val checks = result.optJSONArray("image_checks")
                    if (checks != null) for (i in 0 until checks.length()) {
                        val warnings = checks.getJSONObject(i).optJSONArray("warnings") ?: continue
                        for (j in 0 until warnings.length()) Text("• ${warnings.optString(j)}", style = MaterialTheme.typography.bodySmall)
                    }
                    val lines = result.optJSONArray("text_lines") ?: result.optJSONArray("title_evidence")
                    if (lines != null && lines.length() > 0) {
                        Text("Visible text · tap a line to edit it as the title", style = MaterialTheme.typography.labelMedium)
                        for (i in 0 until minOf(lines.length(), 25)) TextButton(onClick = { title = lines.optString(i).take(240) }) { Text(lines.optString(i), fontSize = 12.sp) }
                    }
                    result.optJSONObject("usage")?.let { usage -> Text("API tokens: ${usage.optLong("input_tokens")} input · ${usage.optLong("output_tokens")} output", style = MaterialTheme.typography.bodySmall) }
                    if (latest.state == "SUCCEEDED" && current) {
                        OutlinedTextField(title, { title = it.take(240) }, label = { Text("Verified printed title") })
                        TextButton(enabled = title.isNotBlank(), onClick = { vm.action { repo.confirmRecognitionTitle(latest.id, title); vm.message = "Title confirmed in capture metadata. Inventory and pricing are unchanged." } }) { Text(if (latest.acceptedTitle != null) "Update confirmed title" else "Confirm title") }
                    }
                    if (latest.acceptedTitle != null) Text("Confirmed: ${latest.acceptedTitle}", color = MaterialTheme.colorScheme.primary)
                }
                if (rows.any { it.state == "SUCCEEDED" && it.inputFingerprint == fingerprint }) TextButton(onClick = { vm.action {
                    val file = withContext(Dispatchers.IO) {
                        val payload = JSONObject().put("format", "snapims.mobile.recognition").put("version", 1).put("session_id", group.sessionId).put("capture_group_id", group.id).put("recognition", repo.recognitionManifest(group.id, 10))
                        val target = File(repo.archiveDir, "recognition-${group.id}.json"); val pending = File(repo.archiveDir, "recognition-${group.id}.pending")
                        FileOutputStream(pending).use { it.write(payload.toString(2).toByteArray()); it.fd.sync() }; atomicMove(pending, target); repo.sync(repo.archiveDir); target
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); clipData = android.content.ClipData.newRawUri("Recognition results", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share recognition results"))
                } }) { Text("Share results JSON") }
            } else Text("Run recognition when you are ready. Camera capture never waits for it.", style = MaterialTheme.typography.bodySmall)
            Text("Existing ZIP archives stay unchanged. Newly available results can be shared separately.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !submitting && latest?.state !in listOf("QUEUED", "RUNNING") && repo.recognitionMode() != "OFF", onClick = {
        submitting = true; vm.action { try { repo.queueRecognition(group.id) } finally { submitting = false } }
    }) { Text(when (repo.recognitionMode()) { "OPENAI" -> "Run OpenAI"; "OCR" -> "Run offline OCR"; "CLASSICAL" -> "Run checks"; else -> "Enable in Settings" }) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
