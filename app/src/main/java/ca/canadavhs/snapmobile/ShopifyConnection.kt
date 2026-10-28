package ca.canadavhs.snapmobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

internal class ShopifyBrokerException(val code:String,message:String,val httpStatus:Int=0,val retryAfter:Int=0):IllegalStateException(message)
internal interface ShopifyGateway {suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject;suspend fun upload(target:JSONObject,file:java.io.File)}
internal class ShopifyDeviceIdentity(private val context:Context) {
    private val preferences=context.getSharedPreferences("shopify-device",0)
    private val alias="snapims.shopify.device-signature.v1"
    val deviceId:String get()=preferences.getString("id",null) ?: newId().also {check(preferences.edit().putString("id",it).commit())}
    private fun pair():KeyPair {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? PrivateKey)?.let {return KeyPair(store.getCertificate(alias).publicKey,it)}
        return KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply {initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())}.generateKeyPair()
    }
    fun publicKey():String=Base64.getUrlEncoder().withoutPadding().encodeToString(pair().public.encoded)
    fun sign(message:ByteArray):String=Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withECDSA").run {initSign(pair().private);update(message);sign()})
    fun forget() {KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)};check(preferences.edit().clear().commit())}
}
/** Temporary signed upload targets are device-encrypted and redacted from snapshots. */
internal class ShopifyUploadVault(context:Context,private val suppliedKey:(()->javax.crypto.SecretKey)?=null) {
    private val alias="snapims.shopify.upload-targets.v1"
    private fun key():javax.crypto.SecretKey {
        suppliedKey?.let {return it()}
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? javax.crypto.SecretKey)?.let {return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())}.generateKey()
    }
    fun encrypt(identity:String,value:JSONObject):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key());updateAAD(identity.toByteArray())}
        return Base64.getEncoder().encodeToString(cipher.iv+cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(identity:String,value:String):JSONObject {
        try {val bytes=Base64.getDecoder().decode(value);require(bytes.size in 29..131072)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(identity.toByteArray())}
            return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        } catch(_:Exception) {throw ShopifyBrokerException("RESTAGE_REQUIRED","Upload credentials cannot be unlocked on this device; re-stage or reconcile this photo.")}
    }
}
internal class ShopifyBrokerClient(private val context:Context,val base:String,private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).followRedirects(false).build()):ShopifyGateway {
    private val identity=ShopifyDeviceIdentity(context)
    init {requireBrokerUrl(base)}
    private suspend fun exchange(path:String,value:JSONObject,signed:Boolean=true):JSONObject=withContext(Dispatchers.IO) {
        val body=value.toString().toByteArray(Charsets.UTF_8)
        val request=Request.Builder().url(base+path).post(body.toRequestBody("application/json".toMediaType())).header("Accept","application/json")
        if(signed) {
            val stamp=(System.currentTimeMillis()/1000).toString();val nonce=SiwcProtocol.random();val device=identity.deviceId
            val message=listOf("SNAPIMS1",base,"POST",path,stamp,nonce,sha256(body),device).joinToString("\n").toByteArray()
            request.header("X-SnapIMS-Device",device).header("X-SnapIMS-Time",stamp).header("X-SnapIMS-Nonce",nonce).header("X-SnapIMS-Signature",identity.sign(message))
        }
        val call=http.newCall(request.build())
        coroutineScope {val guard=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
            try {call.execute().use {response ->
                val bytes=response.body?.byteStream()?.use {it.readBounded(2_000_000)} ?: error("Empty Shopify broker response")
                val result=try {JSONObject(String(bytes,Charsets.UTF_8))} catch(_:JSONException) {throw ShopifyBrokerException("BROKER_RESPONSE","Broker returned an invalid response.",response.code)}
                if(!response.isSuccessful) {val error=result.optJSONObject("error");throw ShopifyBrokerException(error?.optString("code") ?: "BROKER_HTTP",error?.optString("message")?.take(1500) ?: "Broker request failed (HTTP ${response.code}).",response.code,error?.optInt("retryAfter") ?: 0)}
                result
            }} catch(e:java.io.IOException) {currentCoroutineContext().ensureActive();throw ShopifyBrokerException("NETWORK","Broker connection interrupted. The saved job will reconcile before repeating a creation.",502)} finally {guard.cancel()}
        }
    }
    suspend fun pair(code:String):JSONObject=exchange("/v1/pair",JSONObject().put("code",code.trim()).put("deviceId",identity.deviceId).put("publicKey",identity.publicKey()),false)
    suspend fun authorize():JSONObject=exchange("/v1/authorize",JSONObject())
    suspend fun revoke():JSONObject=exchange("/v1/revoke",JSONObject())
    override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject=exchange("/v1/execute",JSONObject().put("operationId",operationId).put("operation",operation).put("arguments",args)).let {reply ->
        val value=reply.opt("result")
        if(value==JSONObject.NULL || value==null) JSONObject().put("missing",true) else value as? JSONObject ?: error("Invalid typed broker result")
    }
    override suspend fun upload(target:JSONObject,file:java.io.File)=withContext(Dispatchers.IO) {
        val url=target.getString("url");requireShopifyUploadUrl(url)
        val parts=MultipartBody.Builder().setType(MultipartBody.FORM)
        val parameters=target.getJSONArray("parameters");require(parameters.length()<=50)
        for(i in 0 until parameters.length()) {val p=parameters.getJSONObject(i);parts.addFormDataPart(p.getString("name"),p.getString("value"))}
        parts.addFormDataPart("file",file.name,file.asRequestBodyCompat())
        val call=http.newCall(Request.Builder().url(url).post(parts.build()).build())
        coroutineScope {val guard=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
            try {call.execute().use {response ->if(response.code !in listOf(200,201,204)) throw ShopifyBrokerException(if(response.code in listOf(400,401,403)) "STAGING_EXPIRED" else "UPLOAD_FAILED","Photo staging transfer failed (HTTP ${response.code}). Originals are retained.",response.code)}}
            catch(e:java.io.IOException) {currentCoroutineContext().ensureActive();throw ShopifyBrokerException("NETWORK","Photo transfer interrupted. Original retained; upload can retry safely.",502)} finally {guard.cancel()}
        }
    }
}
private fun java.io.File.asRequestBodyCompat():RequestBody=object:RequestBody() {
    override fun contentType()="image/jpeg".toMediaType()
    override fun contentLength()=this@asRequestBodyCompat.length()
    override fun writeTo(sink:okio.BufferedSink) {inputStream().use {input->val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;sink.write(buffer,0,n)}}}
}
internal fun requireBrokerUrl(value:String) {val uri=java.net.URI(value);require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.path.isNullOrEmpty() && uri.query==null && uri.fragment==null && value.length<=2000) {"Enter the HTTPS origin of your hosted SnapIMS Shopify broker, without a path or trailing slash."}}
internal fun requireShopifyUploadUrl(value:String) {val uri=java.net.URI(value);val host=uri.host.orEmpty().lowercase();val domains=listOf("googleapis.com","amazonaws.com","shopify.com","shopifycdn.com","shopifycdn.net","shopifycloud.com")
    require(uri.scheme=="https" && uri.userInfo==null && uri.port in listOf(-1,443) && uri.fragment==null && domains.any {host==it || host.endsWith(".$it")}) {"Unverified Shopify upload host. No photo was transferred."}}
