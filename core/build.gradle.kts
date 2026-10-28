plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies { implementation("com.google.zxing:core:3.5.4"); testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20250517") }
