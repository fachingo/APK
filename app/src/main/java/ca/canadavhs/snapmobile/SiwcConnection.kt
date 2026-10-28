package ca.canadavhs.snapmobile

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ca.canadavhs.snapmobile.core.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.net.*
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object SiwcConnection {
    val status=MutableStateFlow("Connection not checked this session. Review the proof log and run model discovery.")
    private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(false).build()
    private val refreshLock=Mutex()
    private val credentialLock=Any()
    private var credentialGeneration=0L
    private fun prefs(c:Context)=c.getSharedPreferences("siwc-installation",Context.MODE_PRIVATE)
    private fun host(c:Context):String {val prefs=prefs(c);return prefs.getString("hostId",null) ?: "urn:uuid:${UUID.randomUUID()}".also {check(prefs.edit().putString("hostId",it).commit())}}
    private fun physicalPixel()=Build.MANUFACTURER.equals("Google",true) && Build.MODEL.startsWith("Pixel") && !Build.FINGERPRINT.contains("generic") && !Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")
    suspend fun proof(repo:CaptureRepository,stage:String,detail:String) {repo.journal.nextDao().insert(SiwcProofRow(stage=stage,detail="Attempt ${prefs(repo.context).getString("proofAttempt","unassigned")} · $detail",physicalPixel=physicalPixel()));status.value=detail}
    fun connected(context:Context)=runCatching {SecureCredentialStore(context).read()!=null}.getOrDefault(false)
    suspend fun json(request:Request):JSONObject=withContext(Dispatchers.IO) {http.newCall(request).execute().use {response ->
        if(!response.isSuccessful) throw AiRequestException(response.code,if(response.code==429) "ChatGPT plan limit reached. Wait and retry manually. No API billing was used." else "ChatGPT connection failed (HTTP ${response.code}). Check eligibility or sign in again. No API billing was used.")
        val body=response.body ?: error("Empty ChatGPT response");require(body.contentLength()<=2_000_000)
        JSONObject(String(body.byteStream().use {it.readBounded(2_000_000)},Charsets.UTF_8))
    }}
    private suspend fun verify(token:String,clientId:String,nonce:String):JSONObject {
        val config=json(Request.Builder().url("${SiwcProtocol.origin}/.well-known/openid-configuration").build())
        require(config.getString("issuer")==SiwcProtocol.origin) {"Unsupported OpenID issuer."}
        val keys=URI(config.getString("jwks_uri"));require(keys.scheme=="https" && keys.host=="auth.openai.com" && (keys.port==-1 || keys.port==443) && keys.userInfo==null && keys.fragment==null) {"Unsupported OpenID signing-key endpoint."}
        return SiwcProtocol.validateIdToken(token,json(Request.Builder().url(keys.toString()).build()),clientId,nonce)
    }
    suspend fun authorize(context:Context,repo:CaptureRepository,onBrowser:(String)->Unit)=withContext(Dispatchers.IO) {
        val authorizationGeneration=synchronized(credentialLock) {credentialGeneration}
        check(prefs(context).edit().putString("proofAttempt",UUID.randomUUID().toString()).commit())
        val registered=prefs(context).getString("clientId",null)
        ServerSocket().use {server ->
            server.reuseAddress=false
            try {server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),1455))}
            catch(e:java.io.IOException) {if(registered==null) error("Android loopback port 1455 is unavailable. Initial SIWC registration cannot proceed. Close other local sign-ins and retry.");server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0))}
            server.soTimeout=1000
            val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
            val state=SiwcProtocol.random();val nonce=SiwcProtocol.random();val verifier=SiwcProtocol.random()
            proof(repo,"LISTENER","Loopback listener ready on this device. Waiting for browser authorization.")
            withContext(Dispatchers.Main) {onBrowser(SiwcProtocol.authorize(registered ?: "dynamic_agent_client",host(context),redirect,state,nonce,verifier))}
            val deadline=System.currentTimeMillis()+300000;var callback:Map<String,String>?=null;var attempts=0
            while(callback==null && System.currentTimeMillis()<deadline) {
                currentCoroutineContext().ensureActive()
                val socket=try {server.accept()} catch(_:SocketTimeoutException) {continue}
                socket.use {client ->
                    client.soTimeout=2000;require(++attempts<=32) {"Too many local callback attempts. Start sign-in again."}
                    val input=client.getInputStream();val request=StringBuilder()
                    while(request.length<20000) {val b=input.read();if(b<0) break;request.append(b.toChar());if(request.endsWith("\r\n\r\n")) break}
                    val line=request.toString().substringBefore("\r\n").split(' ')
                    var accepted=false
                    if(line.size==3 && line[0]=="GET" && line[1].startsWith("/auth/callback?")) {
                        val fields=runCatching {SiwcProtocol.callback(line[1].substringAfter('?'),state)}
                        if(fields.isSuccess) {callback=fields.getOrThrow();accepted=true}
                        else if(fields.exceptionOrNull()?.message?.startsWith("ChatGPT authorization was declined")==true) throw fields.exceptionOrNull()!!
                    }
                    val text=if(accepted) "Authorization received. Return to SnapIMS Next to finish verification." else "Invalid callback. Return to SnapIMS Next and retry if needed."
                    val bytes=text.toByteArray();client.getOutputStream().write(("HTTP/1.1 ${if(accepted) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
                }
            }
            val fields=callback ?: error("ChatGPT browser callback timed out. Android loopback compatibility is not yet verified on this device. API-key mode remains available.")
            val clientId=fields["client_id"] ?: registered ?: error("Initial registration did not return an issued client ID. Cannot exchange the authorization code.")
            require(clientId!="dynamic_agent_client" && clientId.length in 1..512)
            if(registered!=null) require(clientId==registered) {"ChatGPT client registration changed. Disconnect and review the installation registration."}
            val form=FormBody.Builder().add("grant_type","authorization_code").add("client_id",clientId).add("code",fields.getValue("code"))
                .add("code_verifier",verifier).add("redirect_uri",redirect).add("resource",SiwcProtocol.resource).build()
            val tokens=json(Request.Builder().url("${SiwcProtocol.origin}/api/accounts/oauth/token").post(form).build())
            require(tokens.getString("token_type").equals("Bearer",true))
            val scopes=tokens.getString("scope").split(' ');require(SiwcProtocol.direct in scopes) {"ChatGPT direct plan usage was not granted. Verify account eligibility."}
            val identity=verify(tokens.getString("id_token"),clientId,nonce)
            val priorSubject=prefs(context).getString("subjectHash",null);val subjectHash=sha256(identity.getString("sub").toByteArray())
            require(priorSubject==null || subjectHash==priorSubject) {"A different ChatGPT account signed in. Explicitly forget the saved account before changing accounts."}
            require(tokens.getLong("expires_in") in 1..7200) {"Unsupported ChatGPT token lifetime."}
            val envelope=JSONObject().put("accessToken",tokens.getString("access_token")).put("refreshToken",tokens.getString("refresh_token"))
                .put("expiresAt",System.currentTimeMillis()+tokens.getLong("expires_in")*1000).put("clientId",clientId).put("subjectHash",subjectHash).put("nonce",nonce).put("scope",tokens.getString("scope"))
            currentCoroutineContext().ensureActive()
            synchronized(credentialLock) {
                check(credentialGeneration==authorizationGeneration) {"Sign-in was disconnected. Start a new authorization attempt."}
                SecureCredentialStore(context).save(envelope.toString())
                check(prefs(context).edit().putString("clientId",clientId).putString("subjectHash",subjectHash).commit())
            }
            proof(repo,"SIGNED_IN","Verified ID token and direct-usage scope. Account ${subjectHash.take(12)}. Model discovery is next.")
        }
    }
    suspend fun token(context:Context):String=refreshLock.withLock {
        val store=SecureCredentialStore(context)
        val (refreshGeneration,old)=synchronized(credentialLock) {credentialGeneration to JSONObject(store.read() ?: error("Continue with ChatGPT to connect this device. API keys are a separate connection."))}
        if(old.getLong("expiresAt")>System.currentTimeMillis()+60000) return@withLock old.getString("accessToken")
        val form=FormBody.Builder().add("grant_type","refresh_token").add("client_id",old.getString("clientId")).add("refresh_token",old.getString("refreshToken")).add("resource",SiwcProtocol.resource).build()
        val result=json(Request.Builder().url("${SiwcProtocol.origin}/api/accounts/oauth/token").post(form).build())
        require(result.getString("token_type").equals("Bearer",true))
        val scope=result.optString("scope",old.getString("scope"));require(SiwcProtocol.direct in scope.split(' ')) {"Direct plan scope is no longer granted. Sign in again."}
        if(result.has("id_token")) {
            val identity=verify(result.getString("id_token"),old.getString("clientId"),old.getString("nonce"))
            require(sha256(identity.getString("sub").toByteArray())==old.getString("subjectHash")) {"Refreshed ChatGPT account identity changed. Sign in again."}
        }
        require(result.getLong("expires_in") in 1..7200) {"Unsupported ChatGPT token lifetime."}
        require(result.getString("refresh_token").isNotBlank()) {"Refresh token rotation was not returned. Sign in again."}
        val rotated=SiwcProtocol.rotatedCredentials(old,result,System.currentTimeMillis())
        synchronized(credentialLock) {
            check(credentialGeneration==refreshGeneration) {"ChatGPT was disconnected during refresh. Sign in again."}
            store.save(rotated.toString()) // Commit the rotated refresh token before giving callers the access token.
        }
        rotated.getString("accessToken")
    }
    fun disconnect(context:Context,forget:Boolean=false) {synchronized(credentialLock) {
        credentialGeneration++;SecureCredentialStore(context).remove()
        if(forget) check(prefs(context).edit().remove("clientId").remove("subjectHash").commit())
        status.value="Disconnected locally. API-key billing remains separate."
    }}
}
class SiwcSignInService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var authorization:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="CANCEL") {scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        if(authorization?.isActive==true) return START_NOT_STICKY
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("siwc-signin","ChatGPT sign-in",NotificationManager.IMPORTANCE_LOW))
        val cancel=PendingIntent.getService(this,1,Intent(this,SiwcSignInService::class.java).setAction("CANCEL"),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"siwc-signin").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Continue with ChatGPT")
            .setContentText("Waiting for secure browser sign-in · expires in 5 minutes").addAction(0,"Cancel",cancel).setOngoing(true).build()
        startForeground(1455,notification)
        val repo=(application as CaptureApplication).repository
        authorization=scope.launch {try {SiwcConnection.authorize(this@SiwcSignInService,repo) {url ->startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
            catch(e:CancellationException) {SiwcConnection.status.value="Sign-in cancelled.";throw e}
            catch(e:Exception) {val reason=if(e is IllegalStateException || e is IllegalArgumentException || e is AiRequestException) e.message?.take(400) else null
                SiwcConnection.proof(repo,"SIGN_IN_FAILED",(reason ?: "Sign-in could not complete. Check browser authorization, eligibility and loopback compatibility.")+" Retry manually. No API key was used.")}
            finally {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
        return START_NOT_STICKY
    }
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}
