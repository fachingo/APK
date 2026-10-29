package ca.canadavhs.snapmobile

import android.content.Context
import org.json.JSONObject
internal fun directShopifyEndpoint(value:String):Boolean=runCatching {val u=java.net.URI(value);u.scheme=="https" && u.host?.matches(Regex("[a-z0-9][a-z0-9-]*\\.myshopify\\.com"))==true && u.path.isNullOrEmpty() && u.port==-1 && u.userInfo==null && u.query==null && u.fragment==null}.getOrDefault(false)
internal fun directShopify(context:Context):DirectShopifyGateway=(context.applicationContext as? CaptureApplication)?.repository?.directShopifyFactory?.invoke(context) ?: DirectShopifyGateway(context)
internal fun shopifyGateway(context:Context,base:String):ShopifyGateway {
 if(directShopifyEndpoint(base)) {val direct=directShopify(context);check(direct.endpoint()==base) {"Reconnect the original Shopify store; this job cannot change stores or use a broker."};return direct}
 return ShopifyBrokerClient(context,base)
}
internal fun CaptureRepository.shopifyEndpoint():String=if(preferences.getBoolean("shopifyDirect",false)) directShopifyFactory(context).endpoint() else preferences.getString("shopifyBroker","").orEmpty()
internal fun CaptureRepository.requireShopifyWriteHealth(health:JSONObject) {
 check(health.getString("currency")=="CAD") {"Shopify prices require a CAD store; no implicit conversion is permitted."}
 check(health.getBoolean("developmentStore") || (health.optBoolean("direct") && health.optBoolean("allowProduction") && preferences.getBoolean("shopifyDirect",false) && preferences.getBoolean("shopifyOwnerWrites",false))) {"Enable owner-operated Shopify writes in Settings, then test the connection. Each operation still requires review."}
}
