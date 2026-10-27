# Third-party notices

The APK includes the original runtime library notice payloads under
`assets/licenses/`, accessible in Settings → Third-party notices & licenses.
Long files are paged so opening legal notices cannot create an unbounded text layout.

- [Runtime component/license inventory](RUNTIME_LICENSES.json): 193 resolved components,
  including BOM/metadata modules. License metadata is resolved from their Maven POMs,
  inherited parent POMs, or the original javax.inject source header.
- [Packaged notices](../app/src/main/assets/licenses/NOTICE.txt): primary attributions
  and links to Google ML Kit/Android SDK terms.
- Apache 2.0, BSD-3-Clause and MIT full license texts are included.
- Original Google ML Kit / Play services third_party_licenses text and metadata are
  retained with component/version names.
- BUNDLED_NOTICES.txt preserves 106 distributed license/notice files from resolved
  runtime JAR/AAR artifacts, including CameraX's BSD attribution and checker-qual's
  MIT notice. Original payloads remain authoritative.

Kotlin/Compose/CameraX/Room/WorkManager/Lifecycle/Activity/ExifInterface, Coil and
kotlinx.coroutines remain third-party components. No movie artwork, proprietary
catalog database, custom typeface or third-party photograph is shipped.

The JDK/Android SDK/Gradle distributions retain their original tooling licenses
in the cloud tools directories. Test dependencies are used by test artifacts, not
the main application. No release signing key or API credential is bundled.
