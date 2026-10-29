package ca.canadavhs.snapmobile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=CaptureApplication::class)
class DirectShopifyModelTest {
 @Test fun disconnectInDirectModeClearsCredentialsWithoutBrokerRevocation() {
  val app=RuntimeEnvironment.getApplication() as CaptureApplication
  val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
  val vault=SecureCredentialStore(app,"shopify-direct") {key};vault.save("{\"fixture\":true}")
  val prefs=app.repository.preferences;prefs.edit().clear().putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).commit()
  val model=ShopifyModel(app);model.disconnect(true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
  assertNull(vault.read());assertFalse(prefs.getBoolean("shopifyDirect",true));assertFalse(model.paired);assertTrue(model.message.contains("cleared locally"))
  app.repository.journal.close()
 }
}
