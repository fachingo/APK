# Build and APK artifacts

The Android project remains in fachingo/APK and ports the inspected fachingo/SnapIMS
rules. The desktop checkout is left operational and unchanged. Build with JDK 21,
Android SDK platforms;android-37.0 / Build Tools 36.0.0 and the checked-in wrapper:

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:lintDebug
```

Run lint after kapt/test compilation finishes; the legacy AGP/kapt combination can
race when lint reads temporary stubs while test compilation regenerates them.
No lint checks are disabled. Production work should eventually move to the current
built-in Kotlin DSL; that toolchain migration is not mixed into inventory changes.

.github/workflows/android.yml runs these checks and uploads APKs and reports using
GitHub Actions. It needs no Shopify/OpenAI credentials and never publishes listings.
The workflow file is delivered; a successful local build is not a claimed GitHub
Actions run. Trigger it in Actions after the source is pushed.

For CI APKs to update the owner's existing official debug APK, configure the repository
secret SNAPIMS_DEBUG_KEYSTORE_BASE64 with the retained existing debug keystore via
GitHub Settings → Secrets. Never commit that keystore, print it, or send it in chat.
Without it CI generates a different debug identity; its APK is a test artifact and
cannot update an installed official APK. Official downloadable updates built in this
workspace continue using the retained matching signing identity. Private production
signing and distribution remain separate from debug acceptance builds.

## Market data validation (0.5.0)

Normal host tests generate100,000 explicit synthetic source observations, verify
indexed queries, duplicate-safe repeat import and persistent restart. They bundle
no owner market data. To exercise the exact supplied75,054-row Harvester export,
run the native dataset acceptance test with a local private file:

```bash
./gradlew :app:testDebugUnitTest --tests '*MarketDatasetValidationTest' -PmarketCsv=/private/path/trawl_sales.csv
```

That acceptance fixture checks this known supplied dataset's count/currency/hash
preservation and adds it after the100k synthetic fixture. Do not commit the file or
its generated database. Host timings are not physical Pixel performance evidence.
The last report is app/build/fixture-output/market-benchmark.json; copy it before
another test run replaces results. Full tests/build then lint remain sequential.
