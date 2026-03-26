# EchoStack Android SDK

Mobile attribution SDK for Android. Tracks ad clicks to app installs with Google Play Install Referrer support for deterministic matching.

## Installation

### Gradle (via JitPack)

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.echostack:echostack-android:1.0.0")
}
```

### Requirements

- Android API 21+ (Android 5.0)
- Kotlin 1.9+

## Quick Start

```kotlin
import com.echostack.sdk.EchoStack
import com.echostack.sdk.EventTypes

// 1. Configure in Application.onCreate()
EchoStack.configure(context = this, apiKey = "es_live_...")

// 2. Track events
EchoStack.sendEvent(EventTypes.PURCHASE, mapOf(
    "revenue" to 29.99,
    "currency" to "USD"
))

// 3. Get attribution
val attribution = EchoStack.getAttributionParams()
```

## Features

- **Google Play Install Referrer** — deterministic gclid matching
- **Minimal dependencies** — only installreferrer + coroutines
- **Offline-first** — events queued in SharedPreferences
- **Lifecycle-aware** — auto-flush on Activity.onPause
- **ProGuard compatible** — consumer rules included
- **< 100KB** AAR size

## License

MIT
