package ca.canadavhs.snapmobile

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.*
import java.math.BigInteger
import java.util.Base64

/** Protocol contract supplied by the owner from the official SIWC preview docs, 2026-10-28. */
internal object SiwcProtocol {
    const val origin="https://auth.openai.com"
    const val resource="https://api.openai.com/v1"
    const val scope="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val direct="chatgpt.tokens.use.direct"
    fun random():String=ByteArray(32).also {SecureRandom().nextBytes(it)}.let {Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
    fun challenge(verifier:String):String=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
    fun authorize(clientId:String,hostId:String,redirect:String,state:String,nonce:String,verifier:String):String {
        requireLoopback(redirect)
        val values=linkedMapOf("client_id" to clientId,"ext_agent_host_id" to hostId,"response_type" to "code","redirect_uri" to redirect,
            "scope" to scope,"resource" to resource,"state" to state,"nonce" to nonce,"code_challenge" to challenge(verifier),"code_challenge_method" to "S256")
        if(clientId=="dynamic_agent_client") values["agent_name_hint"]="SnapIMS Next"
        return "$origin/api/accounts/authorize?"+values.entries.joinToString("&") {encode(it.key)+"="+encode(it.value)}
    }
    fun requireLoopback(value:String) {val uri=URI(value);require(uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.path=="/auth/callback" && uri.query==null && uri.fragment==null && uri.userInfo==null)}
    fun callback(query:String,state:String):Map<String,String> {
        require(query.length<=16384)
        val fields=linkedMapOf<String,String>()
        query.split('&').forEach {part ->val pieces=part.split('=',limit=2)
            val key=java.net.URLDecoder.decode(pieces[0],"UTF-8");val value=java.net.URLDecoder.decode(pieces.getOrElse(1){""},"UTF-8")
            require(fields.put(key,value)==null) {"Duplicate OAuth callback field."}
        }
        require(MessageDigest.isEqual(state.toByteArray(),fields["state"].orEmpty().toByteArray())) {"Authorization state mismatch. Start sign-in again."}
        if(fields.containsKey("error")) error("ChatGPT authorization was declined or unavailable. Start sign-in again or use the separate API-key option.")
        require(fields["code"].orEmpty().length in 1..8192) {"Authorization callback did not include a code."}
        return fields
    }
    fun rotatedCredentials(old:JSONObject,result:JSONObject,now:Long):JSONObject {
        require(result.getString("token_type").equals("Bearer",true)) {"Unsupported ChatGPT token type."}
        val scope=result.optString("scope",old.getString("scope"))
        require(direct in scope.split(' ')) {"Direct ChatGPT-plan usage was not granted."}
        val access=result.getString("access_token");val refresh=result.getString("refresh_token")
        require(access.isNotBlank() && refresh.isNotBlank() && result.getLong("expires_in") in 1..7200) {"Invalid rotated ChatGPT credentials. Sign in again."}
        return JSONObject(old.toString()).put("accessToken",access).put("refreshToken",refresh).put("expiresAt",now+result.getLong("expires_in")*1000).put("scope",scope)
    }
    fun validateIdToken(jwt:String,jwks:JSONObject,clientId:String,nonce:String,now:Long=System.currentTimeMillis()/1000):JSONObject {
        require(jwt.length<=65536);val parts=jwt.split('.');require(parts.size==3)
        fun decode(value:String)=Base64.getUrlDecoder().decode(value)
        val header=JSONObject(String(decode(parts[0]),Charsets.UTF_8));val claims=JSONObject(String(decode(parts[1]),Charsets.UTF_8))
        val alg=header.getString("alg");require(alg in listOf("RS256","ES256") && !header.has("crit")) {"Unsupported ID-token signature algorithm."}
        val keys=jwks.getJSONArray("keys");val matches=(0 until keys.length()).map {keys.getJSONObject(it)}.filter {it.optString("kid")==header.getString("kid") && it.optString("use","sig")=="sig" && it.optString("alg",alg)==alg}
        require(matches.size==1) {"Could not resolve the ID-token signing key."};val key=matches.single()
        val publicKey:PublicKey=if(alg=="RS256") {
            require(key.getString("kty")=="RSA")
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decode(key.getString("n"))),BigInteger(1,decode(key.getString("e"))))).also {require((it as RSAPublicKey).modulus.bitLength()>=2048)}
        } else {
            require(key.getString("kty")=="EC" && key.getString("crv")=="P-256")
            val params=AlgorithmParameters.getInstance("EC").apply {init(ECGenParameterSpec("secp256r1"))}.getParameterSpec(ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1,decode(key.getString("x"))),BigInteger(1,decode(key.getString("y")))),params))
        }
        var signature=decode(parts[2])
        if(alg=="ES256") {
            require(signature.size==64)
            fun integer(bytes:ByteArray):ByteArray {val raw=BigInteger(1,bytes).toByteArray();return byteArrayOf(2,raw.size.toByte())+raw}
            val sequence=integer(signature.copyOfRange(0,32))+integer(signature.copyOfRange(32,64));signature=byteArrayOf(0x30,sequence.size.toByte())+sequence
        }
        require(Signature.getInstance(if(alg=="RS256") "SHA256withRSA" else "SHA256withECDSA").run {initVerify(publicKey);update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII));verify(signature)}) {"ID-token signature verification failed."}
        require(claims.getString("iss")==origin) {"Unexpected ID-token issuer."}
        val audiences=when(val aud=claims.get("aud")) {is String ->listOf(aud);is org.json.JSONArray ->(0 until aud.length()).map {aud.getString(it)};else ->emptyList()}
        require(clientId in audiences && audiences.distinct().size==audiences.size && (audiences.size==1 || claims.optString("azp")==clientId) && (!claims.has("azp") || claims.getString("azp")==clientId)) {"Unexpected ID-token audience."}
        require(claims.getLong("exp")>now-60 && claims.getLong("iat")<=now+60 && (!claims.has("nbf") || claims.getLong("nbf")<=now+60)) {"ID token is expired or not yet valid."}
        require(MessageDigest.isEqual(claims.getString("nonce").toByteArray(),nonce.toByteArray())) {"ID-token nonce mismatch."}
        require(claims.getString("sub").isNotBlank())
        return claims
    }
}
