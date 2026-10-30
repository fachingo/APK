package ca.canadavhs.snapmobile

import org.json.*

/** Explicit allowlist: no credential slots, owner-write switches, or authorization receipts. */
internal fun CaptureRepository.exportWorkflowPreferences():JSONObject=JSONObject().put("version",1).apply {
 val models=JSONObject();for(name in listOf("DEFAULT")+AiTask.entries.map {it.name}) preferences.getString("aiTask.$name",null)?.let {models.put(name,TaskModelChoice.parse(JSONObject(it)).json())};put("models",models)
 put("pricingRules",autoPriceRules().json());put("publications",JSONArray(preferences.getStringSet("shopifyDefaultPublications",emptySet()).orEmpty().sorted()));put("location",preferences.getString("shopifyDefaultLocation","").orEmpty())
}
internal fun validateWorkflowPreferences(value:JSONObject) {
 require(value.keys().asSequence().toSet()==setOf("version","models","pricingRules","publications","location") && value.getInt("version")==1)
 val models=value.getJSONObject("models");require(models.keys().asSequence().all {it in listOf("DEFAULT")+AiTask.entries.map {t->t.name}});models.keys().forEach {TaskModelChoice.parse(models.getJSONObject(it))};AutoPriceRules.parse(value.getJSONObject("pricingRules"))
 val channels=value.getJSONArray("publications");require(channels.length()<=50);for(i in 0 until channels.length()) require(channels.getString(i).matches(Regex("gid://shopify/Publication/[0-9]{1,30}")))
 val location=value.getString("location");require(location.isEmpty() || location.matches(Regex("gid://shopify/Location/[0-9]{1,30}")))
}
internal fun CaptureRepository.restoreWorkflowPreferences(value:JSONObject) {
 validateWorkflowPreferences(value);val editor=preferences.edit();val models=value.getJSONObject("models");for(name in listOf("DEFAULT")+AiTask.entries.map {it.name}) {val key="aiTask.$name";if(models.has(name)) editor.putString(key,models.getJSONObject(name).toString()) else editor.remove(key)}
 val channels=value.getJSONArray("publications");editor.putStringSet("shopifyDefaultPublications",(0 until channels.length()).map {channels.getString(it)}.toSet()).putString("shopifyDefaultLocation",value.getString("location")).putString("autoPriceRules",value.getJSONObject("pricingRules").toString());check(editor.commit()) {"Cannot restore workflow preferences"}
}
