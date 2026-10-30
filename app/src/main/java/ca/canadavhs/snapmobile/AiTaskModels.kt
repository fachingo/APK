package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*

internal enum class AiTask(val label:String,val images:Boolean=false,val tools:Boolean=false) {
 RECOGNITION("VHS photographic recognition",true), MARKET("Market candidate matching / reranking"), EDITION("Difficult edition identification",true), CONTENT("Product descriptions / listing content"), CHAT("General assistant / chat"), AGENT("Bulk inventory agent workflows",tools=true)
}
internal data class TaskModelChoice(val model:String,val reasoning:String="",val inputUsdPerMillion:String="",val outputUsdPerMillion:String="",val maxOutput:Int=1500) {
 fun json()=JSONObject().put("model",model).put("reasoning",reasoning).put("inputUsdPerMillion",inputUsdPerMillion).put("outputUsdPerMillion",outputUsdPerMillion).put("maxOutput",maxOutput)
 fun validate():TaskModelChoice {require(validOpenAiModelId(model));require(reasoning in setOf("","none","minimal","low","medium","high","xhigh"));require(maxOutput in 128..8000);for(value in listOf(inputUsdPerMillion,outputUsdPerMillion)) if(value.isNotBlank()) require(value.toBigDecimal().let {it>=java.math.BigDecimal.ZERO && it<=java.math.BigDecimal("10000")});return this}
 companion object {fun parse(o:JSONObject)=TaskModelChoice(o.getString("model"),o.optString("reasoning"),o.optString("inputUsdPerMillion"),o.optString("outputUsdPerMillion"),o.optInt("maxOutput",1500)).validate()}
}
internal fun CaptureRepository.taskModel(task:AiTask):TaskModelChoice {
 val configured=preferences.getString("aiTask.${task.name}",null)
 if(configured!=null) return TaskModelChoice.parse(JSONObject(configured))
 val fallback=preferences.getString("aiTask.DEFAULT",null)?.let {TaskModelChoice.parse(JSONObject(it))}
 if(fallback!=null) return fallback
 val legacy=when(task) {AiTask.RECOGNITION->preferences.getString("openaiModel",DEFAULT_OPENAI_MODEL);AiTask.MARKET->preferences.getString("marketMatchModel",DEFAULT_OPENAI_MODEL);else->DEFAULT_OPENAI_MODEL}.orEmpty().ifBlank {DEFAULT_OPENAI_MODEL}
 return TaskModelChoice(legacy)
}
internal fun CaptureRepository.saveTaskModel(task:AiTask?,choice:TaskModelChoice?) {
 require(task!=null || choice!=null);val key="aiTask.${task?.name ?: "DEFAULT"}";val editor=preferences.edit();if(choice==null) editor.remove(key) else editor.putString(key,choice.validate().json().toString());check(editor.commit())
}
internal suspend fun CaptureRepository.validateTaskModel(task:AiTask,choice:TaskModelChoice) {
 choice.validate();val cap=journal.nextDao().capability("OPENAI",choice.model)
 check(cap?.supportsText==true) {"Validate this OpenAI model's text capability in AI Models & Tasks before using it"}
 if(task.images) check(cap.supportsImages==true) {"Validate image input support for this task's model"}
 if(task.tools) check(cap.supportsTools==true) {"Validate function-tool support for the agent model"}
 if(task in setOf(AiTask.RECOGNITION,AiTask.MARKET,AiTask.CONTENT,AiTask.EDITION)) check(preferences.getBoolean("aiStructured.${choice.model}",false) || cap.origin=="OPENAI_DOCUMENTED_REGISTRY") {"Validate structured output support before using this model for a structured task"}
 if(choice.reasoning.isNotBlank()) check(preferences.getBoolean("aiReasoning.${choice.model}.${choice.reasoning}",false)) {"Validate the chosen reasoning effort first; model families support different levels"}
}
internal suspend fun CaptureRepository.probeTaskModel(task:AiTask,choice:TaskModelChoice) {
 choice.validate();val key=SecureApiKeyStore(context).read() ?: error("Save your OpenAI API key in Recognition settings or AI workspace Connections first")
 val schema=JSONObject("""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""")
 val content=JSONArray().put(JSONObject().put("type","input_text").put("text",if(task.images) "Read the single black digit in this image. Return it as answer." else "Return answer OK. This is a synthetic compatibility test."))
 if(task.images) {val bitmap=android.graphics.Bitmap.createBitmap(100,100,android.graphics.Bitmap.Config.ARGB_8888);val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);canvas.drawText("7",30f,70f,android.graphics.Paint().apply {color=android.graphics.Color.BLACK;textSize=48f});val bytes=java.io.ByteArrayOutputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};bitmap.recycle();content.put(JSONObject().put("type","input_image").put("image_url","data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(bytes)))}
 val body=JSONObject().put("model",choice.model).put("store",false).put("max_output_tokens",1000).put("input",JSONArray().put(JSONObject().put("role","user").put("content",content))).put("text",JSONObject().put("format",JSONObject().put("type","json_schema").put("name","compatibility").put("strict",true).put("schema",schema)))
 if(choice.reasoning.isNotBlank()) body.put("reasoning",JSONObject().put("effort",choice.reasoning))
 val response=HttpsOpenAiTransport().execute(key,"responses",body);check(response.status in 200..299) {"OpenAI rejected this compatibility test (HTTP ${response.status}); check model, inputs, reasoning and account permissions"}
 val data=JSONObject(response.body);check(data.getString("status")=="completed");val text=StringBuilder();val output=data.getJSONArray("output");for(i in 0 until output.length()) {val parts=output.getJSONObject(i).optJSONArray("content") ?: continue;for(j in 0 until parts.length()) if(parts.getJSONObject(j).optString("type")=="output_text") text.append(parts.getJSONObject(j).getString("text"))}
 check(JSONObject(text.toString()).getString("answer")==if(task.images) "7" else "OK") {"Compatibility test inconclusive; task remains unverified"}
 val prior=journal.nextDao().capability("OPENAI",choice.model)
 journal.nextDao().save((prior ?: AiCapabilityRow(stableRowId("OPENAI/${choice.model}"),"OPENAI",choice.model,choice.model)).copy(supportsText=true,supportsImages=if(task.images) true else prior?.supportsImages,origin="EXPLICIT_DEVICE_PROBE"))
 preferences.edit().putBoolean("aiStructured.${choice.model}",true).apply();if(choice.reasoning.isNotBlank()) preferences.edit().putBoolean("aiReasoning.${choice.model}.${choice.reasoning}",true).apply()
 // Agent tools have a separate actual function-call probe in AI workspace Connections.
}
@Composable internal fun AiTaskModelsSettings(vm:CaptureViewModel) {
 var open by rememberSaveable {mutableStateOf(false)};var selected by rememberSaveable {mutableStateOf<AiTask?>(null)};var isDefault by rememberSaveable {mutableStateOf(true)}
 var choice by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.Saver<TaskModelChoice,String>(save={it.json().toString()},restore={TaskModelChoice.parse(JSONObject(it))})) {mutableStateOf(TaskModelChoice(DEFAULT_OPENAI_MODEL))};var notice by remember {mutableStateOf("")};var probe by remember {mutableStateOf(false)};var catalog by remember {mutableStateOf<List<String>>(emptyList())}
 OutlinedButton(onClick={open=true;isDefault=true;choice=vm.repo.preferences.getString("aiTask.DEFAULT",null)?.let {TaskModelChoice.parse(JSONObject(it))} ?: TaskModelChoice(DEFAULT_OPENAI_MODEL)}) {Text("AI Models & Tasks")}
 if(open) androidx.compose.ui.window.Dialog(onDismissRequest={open=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f)) {Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("AI Models & Tasks",style=MaterialTheme.typography.headlineSmall);Text("OpenAI API models only here. Offline matching/pricing never uses a model. Existing free/local/chat connections remain available in AI workspace.")
  ChoiceField("Task",if(isDefault) "Default" else selected!!.label,listOf("Default")+AiTask.entries.map {it.label}) {label->isDefault=label=="Default";selected=AiTask.entries.firstOrNull {it.label==label};choice=selected?.let {vm.repo.taskModel(it)} ?: vm.repo.preferences.getString("aiTask.DEFAULT",null)?.let {TaskModelChoice.parse(JSONObject(it))} ?: TaskModelChoice(DEFAULT_OPENAI_MODEL)}
  OutlinedTextField(choice.model,{choice=choice.copy(model=it.take(160))},label={Text("OpenAI model ID")},singleLine=true)
  OutlinedButton(onClick={vm.action {val rows=AiClient(vm.repo.context).models(AiProvider.get("OPENAI"));rows.forEach {vm.repo.journal.nextDao().save(it)};catalog=rows.map {it.model};notice="${catalog.size} models discovered; catalog alone does not prove capabilities"}}) {Text("Discover OpenAI models")}
  if(catalog.isNotEmpty()) ChoiceField("Discovered models",choice.model,catalog) {choice=choice.copy(model=it)}
  ChoiceField("Reasoning effort · only when supported",choice.reasoning.ifBlank {"Provider default"},listOf("Provider default","none","minimal","low","medium","high","xhigh")) {choice=choice.copy(reasoning=if(it=="Provider default") "" else it)}
  OutlinedTextField(choice.maxOutput.toString(),{n->n.toIntOrNull()?.takeIf {it in 128..8000}?.let {choice=choice.copy(maxOutput=it)}},label={Text("Maximum output tokens for authorized task requests")})
  OutlinedTextField(choice.inputUsdPerMillion,{choice=choice.copy(inputUsdPerMillion=it.take(24))},label={Text("Verified input USD / million tokens · optional")})
  OutlinedTextField(choice.outputUsdPerMillion,{choice=choice.copy(outputUsdPerMillion=it.take(24))},label={Text("Verified output USD / million tokens · optional")})
  Text("Enter current verified prices for batch budget estimates. Prices are operator configuration, not a fetched tariff. Unknown costs remain unknown. Agent tool support can be tested separately in AI workspace Connections.",style=MaterialTheme.typography.bodySmall)
  Row {Button(onClick={vm.action {vm.repo.saveTaskModel(if(isDefault) null else selected,choice);notice="Saved this task only. Existing conversations keep their selected model."}}) {Text("Save model")};if(!isDefault) TextButton(onClick={vm.repo.saveTaskModel(selected,null);choice=vm.repo.taskModel(selected!!);notice="Using default for this task"}) {Text("Use default")}}
  OutlinedButton(onClick={probe=true}) {Text("Review compatibility test")};Text(notice);TextButton(onClick={open=false}) {Text("Done")}
 }}}
 if(probe) AlertDialog(onDismissRequest={probe=false},title={Text("Authorize one OpenAI compatibility request?")},text={Text("${choice.model} · synthetic ${if(selected?.images==true) "image and " else ""}structured-output test, up to 1,000 output tokens. API charges may apply. No inventory or photographs are sent.")},confirmButton={Button(onClick={probe=false;vm.action {vm.repo.probeTaskModel(selected ?: AiTask.MARKET,choice);notice="Compatibility test passed. Agent tool support requires the separate tool probe."}}) {Text("Run test")}},dismissButton={TextButton(onClick={probe=false}) {Text("Cancel")}})
}
