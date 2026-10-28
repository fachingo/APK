package ca.canadavhs.snapmobile

import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.*
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class SiwcProtocolTest {
    @Test fun pkceMatchesRfc7636AndUsesLoopbackOnly() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",SiwcProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url=SiwcProtocol.authorize("dynamic_agent_client","urn:uuid:test","http://127.0.0.1:1455/auth/callback","state","nonce","verifier")
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"));assertTrue(url.contains("agent_name_hint=SnapIMS+Next"));assertTrue(url.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"));assertTrue(url.contains("code_challenge_method=S256"));assertFalse(url.contains("client_secret"))
        for(bad in listOf("snapims://callback","http://localhost:1455/auth/callback","http://0.0.0.0:1455/auth/callback","http://127.0.0.1:1455/other","https://127.0.0.1:1455/auth/callback")) try {SiwcProtocol.requireLoopback(bad);fail(bad)} catch(_:IllegalArgumentException) {}
    }
    @Test fun callbackRejectsWrongStateDuplicateFieldsAndDeclinedAuth() {
        assertEquals("issued",SiwcProtocol.callback("state=correct&code=one&client_id=issued","correct")["client_id"])
        for(query in listOf("state=wrong&code=one","state=correct&state=correct&code=one","state=correct&error=access_denied","state=correct")) try {SiwcProtocol.callback(query,"correct");fail(query)} catch(_:Exception) {}
    }
    @Test fun idTokenRequiresSignatureAudienceIssuerExpiryAndNonce() {
        val pair=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair();val public=pair.public as RSAPublicKey
        fun encode(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun unsigned(value:java.math.BigInteger)=value.toByteArray().let {if(it[0].toInt()==0) it.copyOfRange(1,it.size) else it}
        val jwks=JSONObject().put("keys",JSONArray().put(JSONObject().put("kty","RSA").put("kid","fixture").put("alg","RS256").put("n",encode(unsigned(public.modulus))).put("e",encode(unsigned(public.publicExponent)))))
        val claims=JSONObject().put("iss",SiwcProtocol.origin).put("aud","issued-client").put("sub","fixture-subject").put("exp",2000).put("iat",900).put("nonce","expected")
        fun signed(value:JSONObject):String {val prefix=encode("{\"alg\":\"RS256\",\"kid\":\"fixture\"}".toByteArray())+"."+encode(value.toString().toByteArray());val signature=Signature.getInstance("SHA256withRSA").apply {initSign(pair.private);update(prefix.toByteArray())}.sign();return prefix+"."+encode(signature)}
        assertEquals("fixture-subject",SiwcProtocol.validateIdToken(signed(claims),jwks,"issued-client","expected",1000).getString("sub"))
        for((field,value) in listOf("iss" to "https://evil.invalid","aud" to "other-client","exp" to 500,"nonce" to "other","azp" to "other-client")) {
            val altered=JSONObject(claims.toString()).put(field,value);try {SiwcProtocol.validateIdToken(signed(altered),jwks,"issued-client","expected",1000);fail(field)} catch(_:IllegalArgumentException) {}
        }
        val tampered=signed(claims).split('.').let {it[0]+"."+encode(JSONObject(claims.toString()).put("sub","attacker").toString().toByteArray())+"."+it[2]}
        try {SiwcProtocol.validateIdToken(tampered,jwks,"issued-client","expected",1000);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun es256IdTokensValidateWithTheOfficialCurveAndRejectTampering() {
        val pair=KeyPairGenerator.getInstance("EC").apply {initialize(java.security.spec.ECGenParameterSpec("secp256r1"))}.generateKeyPair()
        val public=pair.public as java.security.interfaces.ECPublicKey
        fun encode(value:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        fun coordinate(value:java.math.BigInteger):String {val bytes=value.toByteArray();return encode(if(bytes.size>32) bytes.copyOfRange(bytes.size-32,bytes.size) else ByteArray(32-bytes.size)+bytes)}
        val jwks=JSONObject().put("keys",JSONArray().put(JSONObject().put("kty","EC").put("crv","P-256").put("kid","ec-fixture").put("alg","ES256").put("x",coordinate(public.w.affineX)).put("y",coordinate(public.w.affineY))))
        val claims=JSONObject().put("iss",SiwcProtocol.origin).put("aud","issued-client").put("sub","ec-fixture").put("exp",2000).put("iat",900).put("nonce","expected")
        val prefix=encode("{\"alg\":\"ES256\",\"kid\":\"ec-fixture\"}".toByteArray())+"."+encode(claims.toString().toByteArray())
        val signature=Signature.getInstance("SHA256withECDSAinP1363Format").apply {initSign(pair.private);update(prefix.toByteArray())}.sign();assertEquals(64,signature.size)
        assertEquals("ec-fixture",SiwcProtocol.validateIdToken(prefix+"."+encode(signature),jwks,"issued-client","expected",1000).getString("sub"))
        signature[0]=(signature[0].toInt() xor 1).toByte()
        try {SiwcProtocol.validateIdToken(prefix+"."+encode(signature),jwks,"issued-client","expected",1000);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun rotatingCredentialsPreserveIdentityAndRejectRevokedPlanScope() {
        val old=JSONObject().put("scope",SiwcProtocol.scope).put("accessToken","old-fixture").put("refreshToken","old-refresh").put("clientId","issued-client").put("subjectHash","verified-subject")
        val result=JSONObject().put("token_type","Bearer").put("access_token","new-fixture").put("refresh_token","new-refresh").put("expires_in",3600)
        val rotated=SiwcProtocol.rotatedCredentials(old,result,1000);assertEquals("new-refresh",rotated.getString("refreshToken"));assertEquals("old-refresh",old.getString("refreshToken"));assertEquals("issued-client",rotated.getString("clientId"));assertEquals("verified-subject",rotated.getString("subjectHash"));assertEquals(3601000L,rotated.getLong("expiresAt"))
        try {SiwcProtocol.rotatedCredentials(old,JSONObject(result.toString()).put("scope","openid profile"),1000);fail()} catch(_:IllegalArgumentException) {}
        try {SiwcProtocol.rotatedCredentials(old,JSONObject(result.toString()).put("refresh_token",""),1000);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun credentialEnvelopeSupportsRotatingBundlesAndRejectsTampering() {
        val context=RuntimeEnvironment.getApplication();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val store=SecureCredentialStore(context) {key}
        val first=JSONObject().put("accessToken","fixture-access").put("refreshToken","fixture-refresh").put("large","x".repeat(1000)).toString()
        store.save(first);assertEquals(first,store.read());store.save("{\"refreshToken\":\"rotated fixture\"}");assertTrue(store.read()!!.contains("rotated fixture"))
        val prefs=context.getSharedPreferences("siwc-encrypted",0);assertFalse(prefs.getString("envelope","")!!.contains("rotated fixture"));prefs.edit().putString("envelope","tampered").commit()
        try {store.read();fail()} catch(e:IllegalStateException) {assertFalse(e.message!!.contains("fixture-refresh"))};store.remove();assertNull(store.read())
    }
}
