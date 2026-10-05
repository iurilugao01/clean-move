# Clean Move — Foundation (Plan 1 of 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate the data sources with a spike, set up the Android toolchain and project, and ship a working dark-theme app that browses Transcol lines, schedules and itineraries (Ceturb source) with line favorites, plus the pure-logic `ArrivalMerger` that Plan 2 will use.

**Architecture:** Single `app` module, packages `data/ domain/ ui/`. Ceturb data is fetched lazily per line, cached in Room with a TTL, exposed through repository interfaces consumed by Hilt-injected ViewModels. Sources sit behind interfaces so they can be faked in tests and swapped later.

**Tech Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Navigation Compose, Hilt, Room (KSP), DataStore, Ktor Client (OkHttp) + kotlinx.serialization, JUnit4, MockK-free fakes, Turbine, Robolectric, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-05-clean-move-design.md`

**Out of this plan (Plan 2, written after Task 1's findings):** Geocontrol client and DTOs, stop (ponto) catalog, live arrivals, derivation of the scheduled time at a stop, Google Maps screen, stop favorites, About screen, privacy/launch checklist work, dependency upgrade to latest stable and `targetSdk` bump.

## Global Constraints

- App name **Clean Move**; package `com.cleanmove`; `applicationId` `com.cleanmove` (permanent after launch — revisit before publishing).
- Android only; **minSdk 26**; compileSdk/targetSdk 35 in this plan (spec: targetSdk must be the latest stable before Play release — handled in Plan 2).
- Kotlin 2.x with the Compose compiler Gradle plugin; **KSP, not kapt**.
- Single dark theme: near-black background, one accent (teal) for "live", amber for "scheduled / no GPS". Rounded corners, generous spacing.
- Ceturb base URL: `https://api.onibus.ceturb.es.gov.br/api` (old `sistemas.es.gov.br` host redirects). Lines: `ConsultaLinha/?Tipo_Linha=T`; schedules `BuscaHorarios/{linha}`; itinerary `BuscaItinerarios/{linha}`.
- Ceturb schedules only give the departure time at the origin terminal (`Hora_Saida`, `Terminal_Seq`, `Descricao_Hora`). Never invent a per-stop time.
- Parsing must tolerate unknown fields (`ignoreUnknownKeys`) and missing optional fields; bad responses become `Result.failure`, never a crash.
- Each data source fails independently; offline shows cached data; static data TTL = 24 h.
- No secrets in git: `local.properties` is git-ignored.
- Data owners must be credited (Geocontrol, GVBus, Ceturb) — About screen is Plan 2; do not remove this requirement.
- Commit trailer on every commit: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

1. `BuscaHorarios/{linha}` can return `[]` (seen live for line `001`): line detail must show an explicit "no schedules" state, not crash or spin.
2. Duplicate schedule rows differing only by `Tipo_Orientacao` (seen live: `06:00` twice with `A` and `P`): must collapse to one departure whose `notes` = `{A, P}`.
3. Unknown `Descricao_Hora` values and times at/after midnight (`"24:10"`): unknown day type maps to `OTHER`; `"24:10"` normalizes to minute 10; unparsable times are dropped.
4. `Desc_Via` arrives right-padded with spaces: trimmed; blank streets dropped.
5. First launch offline with an empty cache: Search shows an error with retry, not an infinite spinner; with a populated cache it shows cached lines and no error.

---

## File Structure

```
clean-move/
├─ .gitignore
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/libs.versions.toml
├─ scripts/spike/record-fixtures.ps1
├─ docs/spike-findings.md
└─ app/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/AndroidManifest.xml
      ├─ main/kotlin/com/cleanmove/
      │  ├─ CleanMoveApplication.kt
      │  ├─ MainActivity.kt
      │  ├─ di/AppModule.kt
      │  ├─ domain/Models.kt
      │  ├─ domain/ArrivalMerger.kt
      │  ├─ domain/Repositories.kt          (LineCatalog, FavoritesStore interfaces)
      │  ├─ data/ceturb/CeturbDtos.kt
      │  ├─ data/ceturb/CeturbMappers.kt
      │  ├─ data/ceturb/CeturbApi.kt        (CeturbSource interface + Ktor impl)
      │  ├─ data/local/CatalogEntities.kt
      │  ├─ data/local/CatalogDao.kt
      │  ├─ data/local/AppDatabase.kt
      │  ├─ data/repository/LineCatalogRepository.kt
      │  ├─ data/repository/DataStoreFavorites.kt
      │  └─ ui/{theme/Theme.kt, nav/BottomBar.kt, nav/CleanMoveApp.kt,
      │        search/SearchViewModel.kt, search/SearchScreen.kt,
      │        line/LineDetailViewModel.kt, line/LineDetailScreen.kt,
      │        favorites/FavoritesViewModel.kt, favorites/FavoritesScreen.kt,
      │        map/MapPlaceholderScreen.kt}
      └─ test/{kotlin/com/cleanmove/..., resources/fixtures/}
```

Note vs spec: schedules/itineraries are fetched **lazily per line** (on opening a line) and cached with the 24 h TTL, instead of downloading all ~390 lines up front. Same caching rule, far fewer requests.

---

### Task 1: Data spike (validate sources, record fixtures, decide R1/R2)

**Files:**
- Create: `scripts/spike/record-fixtures.ps1`
- Create: `docs/spike-findings.md`
- Create (script output): `app/src/test/resources/fixtures/*.json`

**Interfaces:**
- Consumes: nothing.
- Produces: fixture JSON files and `docs/spike-findings.md` (the verdict that Plan 2 is written from).

- [ ] **Step 1: Write the recording script**

```powershell
# scripts/spike/record-fixtures.ps1
param([string]$PontoId = "")
$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "..\..\app\src\test\resources\fixtures"
New-Item -ItemType Directory -Force $out | Out-Null

function Save($name, $items) {
    $json = ConvertTo-Json -InputObject @($items) -Depth 20
    Set-Content -Path (Join-Path $out $name) -Value $json -Encoding utf8
    Write-Host ("saved {0} ({1} items)" -f $name, @($items).Count)
}
function Shape($label, $items) {
    $first = @($items) | Select-Object -First 1
    Write-Host "--- $label"
    if ($first) { $first | Format-List | Out-String | Write-Host } else { Write-Host "(empty)" }
}
function TryCall($label, [scriptblock]$call) {
    try { & $call } catch { Write-Host "FAILED $label : $($_.Exception.Message)" -ForegroundColor Red; $null }
}

# --- Ceturb (secondary source)
$c = "https://api.onibus.ceturb.es.gov.br/api"
$lines = Invoke-RestMethod "$c/ConsultaLinha/?Tipo_Linha=T"
Save "ceturb_linhas.json" ($lines | Select-Object -First 40)
Save "ceturb_horarios_0010.json" (Invoke-RestMethod "$c/BuscaHorarios/0010")
Save "ceturb_horarios_001_empty.json" (Invoke-RestMethod "$c/BuscaHorarios/001")
Save "ceturb_itinerario_0010.json" (Invoke-RestMethod "$c/BuscaItinerarios/0010")

# --- Geocontrol (primary source) — endpoints from prodest-api-ceturb, unverified
$pontos = TryCall "listarPontosDeParada" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarPontosDeParada" -TimeoutSec 60 }
if ($pontos) { Shape "pontos" $pontos; Write-Host "total pontos: $(@($pontos).Count)"; Save "geo_pontos_sample.json" ($pontos | Select-Object -First 50) }

$area = TryCall "pesquisarPontosDeParada" {
    Invoke-RestMethod -Method Post -ContentType "application/json" -TimeoutSec 60 `
      -Uri "https://buscabus.geocontrol.com.br/svc/json/db/pesquisarPontosDeParada" `
      -Body '{"envelope":[-40.32,-20.28,-40.29,-20.27]}'
}
if ($area) { Shape "pontos por area" $area; Save "geo_pontos_area.json" $area }

$viagens = TryCall "listarViagens" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarViagens" -TimeoutSec 60 }
if ($viagens) { Shape "viagens" $viagens; Write-Host "total viagens: $(@($viagens).Count)"; Save "geo_viagens_sample.json" ($viagens | Select-Object -First 50) }

$assoc = TryCall "listarAssociacaoPontoItinerario" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarAssociacaoPontoItinerario" -TimeoutSec 60 }
if ($assoc) { Shape "associacao ponto-itinerario" $assoc; Write-Host "total assoc: $(@($assoc).Count)"; Save "geo_assoc_sample.json" ($assoc | Select-Object -First 50) }

if ($PontoId) {
    $est = TryCall "obterEstimativasPorOrigem" {
        Invoke-RestMethod -Method Post -ContentType "application/json" -TimeoutSec 60 `
          -Uri "https://buscabus.geocontrol.com.br/svc/estimativas/obterEstimativasPorOrigem" `
          -Body (@{ pontoDeOrigemId = [int]$PontoId } | ConvertTo-Json)
    }
    if ($est) { Shape "estimativas ponto $PontoId" $est; Save "geo_estimativas_$PontoId.json" $est }
}
```

- [ ] **Step 2: Run it without a stop id**

Run (PowerShell, repo root): `powershell -ExecutionPolicy Bypass -File scripts/spike/record-fixtures.ps1`
Expected: Ceturb files saved; for each Geocontrol endpoint either a printed shape (property list of the first item) or a red `FAILED ...` line. Note every property name printed.

- [ ] **Step 3: Pick a stop id and fetch live estimates**

From the printed `pontos` shape, find the property holding the stop id (e.g. `id`/`pontoId`) and take one value from `geo_pontos_sample.json`. Run: `powershell -ExecutionPolicy Bypass -File scripts/spike/record-fixtures.ps1 -PontoId <that id>`
Expected: `geo_estimativas_<id>.json` saved with the arrival-estimate shape (or a FAILED line).

- [ ] **Step 4: Write the findings document**

Create `docs/spike-findings.md` answering, with evidence (property names, counts, sample values) — not guesses:

```markdown
# Spike findings (2026-10-05)

## Endpoint status
| Endpoint | Live? (HTTP/ok/FAILED) | Item count | Notes |
|---|---|---|---|
| ceturb ConsultaLinha | | | |
| ceturb BuscaHorarios | | | |
| ceturb BuscaItinerarios | | | |
| geo listarPontosDeParada | | | |
| geo pesquisarPontosDeParada | | | |
| geo listarViagens | | | |
| geo listarAssociacaoPontoItinerario | | | |
| geo obterEstimativasPorOrigem | | | |

## Response shapes
Property names and types for each Geocontrol response; which property is the stop id, line id/code, minutes-to-arrival, vehicle id/position.

## Line-code mapping
How a Geocontrol line id/code maps to a Ceturb `Linha` (e.g. "0010"). Exact match, padded, or needs a lookup table?

## R1 verdict — scheduled time at a stop
Can per-stop offsets be derived from viagens/associação (e.g. a time offset or sequence + running time per stop)? Yes / No / Partial, with the evidence. If No: Plan 2 shows only "next terminal departure" per line.

## R2 verdict — are the Geocontrol endpoints usable as-is?
Yes / No. If No, state what failed and whether plan B (emulator traffic inspection of br.com.geocontrol.pontual_previsao_cordova) is required.

## Notes for Plan 2
```
Fill every cell.

- [ ] **Step 5: Commit**

```bash
git add scripts docs/spike-findings.md app/src/test/resources/fixtures
git commit -m "spike: record Ceturb/Geocontrol fixtures and findings" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Toolchain and project scaffold

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/cleanmove/CleanMoveApplication.kt`, `app/src/main/kotlin/com/cleanmove/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Test: `app/src/test/kotlin/com/cleanmove/ToolchainSmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: building project with all dependencies from the version catalog (`libs.*` aliases used by later tasks), Hilt application class `CleanMoveApplication`, `MainActivity` hosting `CleanMoveApp()` (defined in Task 3; until then a placeholder `Text`).

- [ ] **Step 1: Install tools**

Run (PowerShell): `winget install --id Google.AndroidStudio -e --accept-package-agreements --accept-source-agreements` then `winget install --id Gradle.Gradle -e`
Then **manual, once:** open Android Studio, finish the Setup Wizard (Standard install: accepts licenses, installs Android SDK + platform-tools + emulator). Afterwards in the SDK Manager ensure **Android SDK Platform 35** and **Build-Tools** are installed.
Verify in a new terminal:
`& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -version` → prints an OpenJDK 17+ version; `gradle --version` → prints Gradle 8.x.
Verify virtualization for the emulator later: `systeminfo | Select-String "Hyper-V"`.

- [ ] **Step 2: Write the version catalog**

```toml
# gradle/libs.versions.toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
hilt = "2.52"
hiltNavCompose = "1.2.0"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
navCompose = "2.8.5"
lifecycle = "2.8.7"
room = "2.6.1"
datastore = "1.1.1"
ktor = "3.0.3"
serialization = "1.7.3"
coroutines = "1.9.0"
junit = "4.13.2"
turbine = "1.2.0"
robolectric = "4.14.1"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavCompose" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core-ktx", version = "1.6.1" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 3: Write the Gradle build files**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "clean-move"
include(":app")
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cleanmove"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cleanmove"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

```
# .gitignore
.gradle/
build/
local.properties
.idea/
*.iml
captures/
.cxx/
*.keystore
*.jks
```

- [ ] **Step 4: Generate the wrapper and point Gradle at the SDK/JDK**

Run (PowerShell, repo root):
```powershell
gradle wrapper --gradle-version 8.10.2
"sdk.dir=$($env:LOCALAPPDATA -replace '\\','\\')\\Android\\Sdk" | Set-Content local.properties -Encoding ascii
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```
Expected: `gradlew.bat` and `gradle/wrapper/` created; `local.properties` contains the SDK path (confirm the folder exists; if the SDK is elsewhere, use that path).

- [ ] **Step 5: Write manifest, resources, Application and Activity**

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".CleanMoveApplication"
        android:label="@string/app_name"
        android:theme="@style/Theme.CleanMove"
        android:allowBackup="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

```xml
<!-- app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">Clean Move</string>
</resources>
```

```xml
<!-- app/src/main/res/values/themes.xml -->
<resources>
    <style name="Theme.CleanMove" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#0B0D10</item>
        <item name="android:statusBarColor">#0B0D10</item>
        <item name="android:navigationBarColor">#0B0D10</item>
    </style>
</resources>
```

```kotlin
// app/src/main/kotlin/com/cleanmove/CleanMoveApplication.kt
package com.cleanmove

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CleanMoveApplication : Application()
```

```kotlin
// app/src/main/kotlin/com/cleanmove/MainActivity.kt
package com.cleanmove

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Clean Move") }
    }
}
```

- [ ] **Step 6: Write the smoke test and run it**

```kotlin
// app/src/test/kotlin/com/cleanmove/ToolchainSmokeTest.kt
package com.cleanmove

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolchainSmokeTest {
    @Test
    fun `unit test toolchain runs`() {
        assertEquals(4, 2 + 2)
    }
}
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ToolchainSmokeTest"`
Expected: BUILD SUCCESSFUL, 1 test passed. (First run downloads dependencies; takes minutes.)

- [ ] **Step 7: Verify the app assembles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` exists. If a dependency version conflict appears, fix the version in the catalog (keep Kotlin/KSP/Compose-compiler in lockstep) and re-run.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: scaffold Android project (Compose, Hilt, Room, Ktor)" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Dark theme and navigation shell

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/ui/theme/Theme.kt`, `ui/nav/BottomBar.kt`, `ui/nav/CleanMoveApp.kt`, `ui/map/MapPlaceholderScreen.kt`
- Modify: `app/src/main/kotlin/com/cleanmove/MainActivity.kt`
- Test: `app/src/test/kotlin/com/cleanmove/ui/nav/BottomBarTest.kt`

**Interfaces:**
- Consumes: Hilt `MainActivity` from Task 2.
- Produces: `enum class Tab(val route: String, val label: String, val icon: ImageVector) { Map, Search, Favorites }`; `@Composable fun BottomBar(selected: Tab?, onSelect: (Tab) -> Unit)`; `@Composable fun CleanMoveTheme(content)`; `object LiveColor/ScheduledColor` constants (`Color`) used by later screens. `CleanMoveApp()` is completed in Task 8 once the three screens exist; in this task it hosts placeholder tab screens.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/nav/BottomBarTest.kt
package com.cleanmove.ui.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cleanmove.ui.theme.CleanMoveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `shows three tabs and reports the tapped one`() {
        var tapped: Tab? = null
        rule.setContent { CleanMoveTheme { BottomBar(selected = Tab.Map, onSelect = { tapped = it }) } }

        rule.onNodeWithText("Mapa").assertExists()
        rule.onNodeWithText("Favoritos").assertExists()
        rule.onNodeWithText("Buscar").performClick()

        assertEquals(Tab.Search, tapped)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.nav.BottomBarTest"`
Expected: FAIL (compilation error: unresolved `Tab`, `BottomBar`, `CleanMoveTheme`).

- [ ] **Step 3: Implement theme, bottom bar, app shell**

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/theme/Theme.kt
package com.cleanmove.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LiveColor = Color(0xFF2DD4BF)
val ScheduledColor = Color(0xFFF5A524)

private val Scheme = darkColorScheme(
    primary = LiveColor,
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF14181D),
    onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Color(0xFF1D232A),
    onSurfaceVariant = Color(0xFF9AA4AE),
    error = Color(0xFFFF6B6B),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun CleanMoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes, content = content)
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/nav/BottomBar.kt
package com.cleanmove.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Map("map", "Mapa", Icons.Filled.Map),
    Search("search", "Buscar", Icons.Filled.Search),
    Favorites("favorites", "Favoritos", Icons.Filled.Star),
}

@Composable
fun BottomBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/map/MapPlaceholderScreen.kt
package com.cleanmove.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MapPlaceholderScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("O mapa chega em breve", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/nav/CleanMoveApp.kt
package com.cleanmove.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cleanmove.ui.map.MapPlaceholderScreen
import com.cleanmove.ui.theme.CleanMoveTheme

@Composable
fun CleanMoveApp() {
    CleanMoveTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route }
        Scaffold(
            bottomBar = {
                if (current != null) {
                    BottomBar(selected = current, onSelect = { tab ->
                        nav.navigate(tab.route) {
                            popUpTo(Tab.Map.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.Map.route, modifier = Modifier.padding(padding)) {
                composable(Tab.Map.route) { MapPlaceholderScreen() }
                composable(Tab.Search.route) { Text("Buscar") }
                composable(Tab.Favorites.route) { Text("Favoritos") }
            }
        }
    }
}
```

Modify `MainActivity.kt`: replace `setContent { Text("Clean Move") }` with `setContent { CleanMoveApp() }` and replace the `androidx.compose.material3.Text` import with `import com.cleanmove.ui.nav.CleanMoveApp`.

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.nav.BottomBarTest"` then `.\gradlew.bat :app:assembleDebug`
Expected: test PASS; assemble SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: dark theme and bottom-navigation shell" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Domain models and `ArrivalMerger`

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/domain/Models.kt`, `domain/ArrivalMerger.kt`
- Test: `app/src/test/kotlin/com/cleanmove/domain/ArrivalMergerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class DayType { WEEKDAY, SATURDAY, SUNDAY_HOLIDAY, ATYPICAL, OTHER }`
  - `data class Line(val code: String, val name: String)`
  - `data class ScheduledDeparture(val lineCode: String, val dayType: DayType, val direction: Int, val terminalName: String, val minuteOfDay: Int, val notes: Set<String>)` (`direction` = Ceturb `Terminal_Seq`, 1 or 2; `minuteOfDay` in 0..1439)
  - `data class ItineraryStep(val lineCode: String, val direction: Int, val sequence: Int, val street: String)`
  - `data class LineDetail(val line: Line, val schedules: List<ScheduledDeparture>, val itinerary: List<ItineraryStep>)`
  - `data class LiveArrival(val lineCode: String, val minutesAway: Int)`
  - `data class ScheduledArrival(val lineCode: String, val minuteOfDay: Int)`
  - `data class Arrival(val lineCode: String, val liveMinutes: Int?, val scheduledMinuteOfDay: Int?)`
  - `class ArrivalMerger(toleranceMinutes: Int = 15, windowMinutes: Int = 60) { fun merge(live: List<LiveArrival>, scheduled: List<ScheduledArrival>, nowMinuteOfDay: Int): List<Arrival> }`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/domain/ArrivalMergerTest.kt
package com.cleanmove.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalMergerTest {
    private val merger = ArrivalMerger()

    @Test
    fun `pairs a live arrival with the nearest scheduled one of the same line`() {
        val result = merger.merge(
            live = listOf(LiveArrival("0010", 7)),
            scheduled = listOf(ScheduledArrival("0010", 8 * 60 + 5), ScheduledArrival("0010", 8 * 60 + 35)),
            nowMinuteOfDay = 8 * 60,
        )
        assertEquals(
            listOf(
                Arrival("0010", 7, 8 * 60 + 5),
                Arrival("0010", null, 8 * 60 + 35),
            ),
            result,
        )
    }

    @Test
    fun `live arrival with no scheduled counterpart keeps scheduled null`() {
        val result = merger.merge(listOf(LiveArrival("0010", 4)), emptyList(), 600)
        assertEquals(listOf(Arrival("0010", 4, null)), result)
    }

    @Test
    fun `scheduled arrival with no live data is kept when inside the window`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 630)), 600)
        assertEquals(listOf(Arrival("0010", null, 630)), result)
    }

    @Test
    fun `scheduled arrival outside the window is dropped`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 600 + 61)), 600)
        assertEquals(emptyList<Arrival>(), result)
    }

    @Test
    fun `scheduled arrival already in the past and unmatched is dropped`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 590)), 600)
        assertEquals(emptyList<Arrival>(), result)
    }

    @Test
    fun `late bus pairs with a slightly past scheduled time`() {
        val result = merger.merge(listOf(LiveArrival("0010", 2)), listOf(ScheduledArrival("0010", 598)), 600)
        assertEquals(listOf(Arrival("0010", 2, 598)), result)
    }

    @Test
    fun `pairing wraps around midnight`() {
        val now = 23 * 60 + 55 // 23:55
        val result = merger.merge(listOf(LiveArrival("0010", 10)), listOf(ScheduledArrival("0010", 5)), now)
        assertEquals(listOf(Arrival("0010", 10, 5)), result)
    }

    @Test
    fun `never pairs across different lines`() {
        val result = merger.merge(
            listOf(LiveArrival("0010", 5)),
            listOf(ScheduledArrival("0022", 605)),
            600,
        )
        assertEquals(listOf(Arrival("0010", 5, null), Arrival("0022", null, 605)), result)
    }

    @Test
    fun `results are sorted by earliest expected arrival`() {
        val result = merger.merge(
            live = listOf(LiveArrival("0022", 12), LiveArrival("0010", 3)),
            scheduled = emptyList(),
            nowMinuteOfDay = 600,
        )
        assertEquals(listOf("0010", "0022"), result.map { it.lineCode })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.domain.ArrivalMergerTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/kotlin/com/cleanmove/domain/Models.kt
package com.cleanmove.domain

enum class DayType { WEEKDAY, SATURDAY, SUNDAY_HOLIDAY, ATYPICAL, OTHER }

data class Line(val code: String, val name: String)

data class ScheduledDeparture(
    val lineCode: String,
    val dayType: DayType,
    val direction: Int,
    val terminalName: String,
    val minuteOfDay: Int,
    val notes: Set<String>,
)

data class ItineraryStep(val lineCode: String, val direction: Int, val sequence: Int, val street: String)

data class LineDetail(
    val line: Line,
    val schedules: List<ScheduledDeparture>,
    val itinerary: List<ItineraryStep>,
)

data class LiveArrival(val lineCode: String, val minutesAway: Int)

data class ScheduledArrival(val lineCode: String, val minuteOfDay: Int)

data class Arrival(val lineCode: String, val liveMinutes: Int?, val scheduledMinuteOfDay: Int?)
```

```kotlin
// app/src/main/kotlin/com/cleanmove/domain/ArrivalMerger.kt
package com.cleanmove.domain

private const val DAY = 1440

class ArrivalMerger(
    private val toleranceMinutes: Int = 15,
    private val windowMinutes: Int = 60,
) {
    fun merge(
        live: List<LiveArrival>,
        scheduled: List<ScheduledArrival>,
        nowMinuteOfDay: Int,
    ): List<Arrival> {
        val codes = (live.map { it.lineCode } + scheduled.map { it.lineCode }).distinct()
        return codes
            .flatMap { code ->
                mergeLine(
                    code,
                    live.filter { it.lineCode == code }.sortedBy { it.minutesAway },
                    scheduled.filter { it.lineCode == code }.map { Math.floorMod(it.minuteOfDay, DAY) },
                    nowMinuteOfDay,
                )
            }
            .sortedBy { eta(it, nowMinuteOfDay) }
    }

    private fun mergeLine(code: String, live: List<LiveArrival>, scheduled: List<Int>, now: Int): List<Arrival> {
        val pool = scheduled.toMutableList()
        val result = mutableListOf<Arrival>()
        for (arrival in live) {
            val expected = Math.floorMod(now + arrival.minutesAway, DAY)
            val match = pool.minByOrNull { circularDistance(it, expected) }
            if (match != null && circularDistance(match, expected) <= toleranceMinutes) {
                pool.remove(match)
                result += Arrival(code, arrival.minutesAway, match)
            } else {
                result += Arrival(code, arrival.minutesAway, null)
            }
        }
        pool.filter { Math.floorMod(it - now, DAY) <= windowMinutes }
            .forEach { result += Arrival(code, null, it) }
        return result
    }

    private fun eta(arrival: Arrival, now: Int): Int =
        arrival.liveMinutes ?: Math.floorMod(arrival.scheduledMinuteOfDay!! - now, DAY)

    private fun circularDistance(a: Int, b: Int): Int =
        minOf(Math.floorMod(a - b, DAY), Math.floorMod(b - a, DAY))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.domain.ArrivalMergerTest"`
Expected: 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(domain): models and ArrivalMerger" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Ceturb DTOs, mappers and API client

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/ceturb/CeturbDtos.kt`, `CeturbMappers.kt`, `CeturbApi.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/ceturb/CeturbMappersTest.kt`, `CeturbApiTest.kt`

**Interfaces:**
- Consumes: domain models from Task 4.
- Produces:
  - DTOs `LineDto`, `ScheduleDto`, `ItineraryDto`.
  - `fun LineDto.toDomain(): Line`; `fun List<ScheduleDto>.toDepartures(): List<ScheduledDeparture>`; `fun List<ItineraryDto>.toSteps(): List<ItineraryStep>`; `fun String.toDayType(): DayType`; `fun parseMinuteOfDay(raw: String): Int?`.
  - `interface CeturbSource { suspend fun lines(): List<LineDto>; suspend fun schedules(code: String): List<ScheduleDto>; suspend fun itinerary(code: String): List<ItineraryDto> }`
  - `class CeturbApi(client: HttpClient, baseUrl: String = CeturbApi.BASE_URL) : CeturbSource` and `fun createCeturbHttpClient(): HttpClient`.

- [ ] **Step 1: Write the failing mapper tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/ceturb/CeturbMappersTest.kt
package com.cleanmove.data.ceturb

import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.ScheduledDeparture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CeturbMappersTest {
    private fun schedule(time: String, day: String = "DIAS ÚTEIS", seq: Int = 1, obs: String? = null, terminal: String = "NOVA PALESTINA") =
        ScheduleDto(terminalName = terminal, dayDescription = day, departureTime = time, lineCode = "0010", terminalSeq = seq, orientation = obs)

    @Test
    fun `maps a line`() {
        assertEquals(Line("0010", "NOVA PALESTINA / FRADINHOS"), LineDto("NOVA PALESTINA / FRADINHOS", "0010").toDomain())
    }

    @Test
    fun `maps day descriptions and falls back to OTHER`() {
        assertEquals(DayType.WEEKDAY, "DIAS ÚTEIS".toDayType())
        assertEquals(DayType.SATURDAY, "SÁBADO".toDayType())
        assertEquals(DayType.SUNDAY_HOLIDAY, "DOMINGOS E FERIADOS".toDayType())
        assertEquals(DayType.ATYPICAL, "ATÍPICOS ENTRE FERIADOS".toDayType())
        assertEquals(DayType.OTHER, "ALGO NOVO".toDayType())
    }

    @Test
    fun `parses minute of day including after-midnight and bad values`() {
        assertEquals(360, parseMinuteOfDay("06:00"))
        assertEquals(10, parseMinuteOfDay("24:10"))
        assertEquals(1439, parseMinuteOfDay("23:59"))
        assertNull(parseMinuteOfDay(""))
        assertNull(parseMinuteOfDay("abc"))
        assertNull(parseMinuteOfDay("12:xx"))
    }

    @Test
    fun `collapses duplicate rows that differ only by orientation`() {
        val departures = listOf(schedule("06:00", obs = "A"), schedule("06:00", obs = "P")).toDepartures()
        assertEquals(
            listOf(ScheduledDeparture("0010", DayType.WEEKDAY, 1, "NOVA PALESTINA", 360, setOf("A", "P"))),
            departures,
        )
    }

    @Test
    fun `keeps different directions and days separate and drops unparsable times`() {
        val departures = listOf(
            schedule("06:00", seq = 1),
            schedule("06:00", seq = 2, terminal = "FRADINHOS"),
            schedule("06:00", day = "SÁBADO"),
            schedule("??"),
        ).toDepartures()
        assertEquals(3, departures.size)
    }

    @Test
    fun `itinerary trims streets drops blanks and orders by direction then sequence`() {
        val steps = listOf(
            ItineraryDto("RUA SÃO PEDRO                 ", "0010", "I", 2),
            ItineraryDto("RUA DO CAJU                   ", "0010", "I", 1),
            ItineraryDto("                              ", "0010", "I", 3),
            ItineraryDto("AV. BRASIL", "0010", "V", 1),
        ).toSteps()
        assertEquals(
            listOf(
                ItineraryStep("0010", 1, 1, "RUA DO CAJU"),
                ItineraryStep("0010", 1, 2, "RUA SÃO PEDRO"),
                ItineraryStep("0010", 2, 1, "AV. BRASIL"),
            ),
            steps,
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.ceturb.CeturbMappersTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement DTOs and mappers**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/ceturb/CeturbDtos.kt
package com.cleanmove.data.ceturb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LineDto(
    @SerialName("Descricao") val description: String = "",
    @SerialName("Linha") val code: String,
)

@Serializable
data class ScheduleDto(
    @SerialName("Desc_Terminal") val terminalName: String = "",
    @SerialName("Descricao_Hora") val dayDescription: String = "",
    @SerialName("Hora_Saida") val departureTime: String = "",
    @SerialName("Linha") val lineCode: String = "",
    @SerialName("Terminal_Seq") val terminalSeq: Int = 1,
    @SerialName("Tipo_Orientacao") val orientation: String? = null,
)

@Serializable
data class ItineraryDto(
    @SerialName("Desc_Via") val street: String = "",
    @SerialName("Linha") val lineCode: String = "",
    @SerialName("Sentido") val direction: String = "",
    @SerialName("Sequencia") val sequence: Int = 0,
)
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/ceturb/CeturbMappers.kt
package com.cleanmove.data.ceturb

import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.ScheduledDeparture

fun LineDto.toDomain(): Line = Line(code = code, name = description.trim())

fun String.toDayType(): DayType = when (trim().uppercase()) {
    "DIAS ÚTEIS" -> DayType.WEEKDAY
    "SÁBADO" -> DayType.SATURDAY
    "DOMINGOS E FERIADOS" -> DayType.SUNDAY_HOLIDAY
    "ATÍPICOS ENTRE FERIADOS" -> DayType.ATYPICAL
    else -> DayType.OTHER
}

/** "06:00" -> 360. Hours >= 24 (after-midnight service) wrap to the next day: "24:10" -> 10. */
fun parseMinuteOfDay(raw: String): Int? {
    val parts = raw.trim().split(":")
    if (parts.size < 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours < 0 || minutes !in 0..59) return null
    return (hours * 60 + minutes) % 1440
}

fun List<ScheduleDto>.toDepartures(): List<ScheduledDeparture> =
    mapNotNull { dto ->
        val minute = parseMinuteOfDay(dto.departureTime) ?: return@mapNotNull null
        Triple(dto, dto.dayDescription.toDayType(), minute)
    }
        .groupBy { (dto, day, minute) -> listOf(dto.lineCode, day, dto.terminalSeq, minute) }
        .values
        .map { group ->
            val (first, day, minute) = group.first()
            ScheduledDeparture(
                lineCode = first.lineCode,
                dayType = day,
                direction = first.terminalSeq,
                terminalName = first.terminalName.trim(),
                minuteOfDay = minute,
                notes = group.mapNotNull { it.first.orientation?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
            )
        }
        .sortedWith(compareBy({ it.dayType }, { it.direction }, { it.minuteOfDay }))

fun List<ItineraryDto>.toSteps(): List<ItineraryStep> =
    filter { it.street.isNotBlank() }
        .map {
            ItineraryStep(
                lineCode = it.lineCode,
                direction = if (it.direction.trim().uppercase() == "I") 1 else 2,
                sequence = it.sequence,
                street = it.street.trim(),
            )
        }
        .sortedWith(compareBy({ it.direction }, { it.sequence }))
```

- [ ] **Step 4: Run mapper tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.ceturb.CeturbMappersTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Write the failing API test**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/ceturb/CeturbApiTest.kt
package com.cleanmove.data.ceturb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CeturbApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(contentType: String, body: String, status: HttpStatusCode = HttpStatusCode.OK, onRequest: (String) -> Unit = {}): CeturbApi {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json, ContentType.Any) } }
        return CeturbApi(client, baseUrl = "https://example.test/api")
    }

    @Test
    fun `lines requests the Transcol list and ignores unknown fields`() = runTest {
        var url = ""
        val api = api("application/json", """[{"Descricao":"NOVA PALESTINA / FRADINHOS","Linha":"0010","Extra":1}]""") { url = it }
        assertEquals(listOf(LineDto("NOVA PALESTINA / FRADINHOS", "0010")), api.lines())
        assertEquals("https://example.test/api/ConsultaLinha/?Tipo_Linha=T", url)
    }

    @Test
    fun `parses json even when the server sends a different content type`() = runTest {
        val api = api("text/html; charset=utf-8", """[{"Descricao":"X","Linha":"0010"}]""")
        assertEquals(1, api.lines().size)
    }

    @Test
    fun `schedules and itinerary hit the per-line endpoints and accept an empty array`() = runTest {
        val urls = mutableListOf<String>()
        val api = api("application/json", "[]") { urls += it }
        assertTrue(api.schedules("001").isEmpty())
        assertTrue(api.itinerary("001").isEmpty())
        assertEquals(
            listOf("https://example.test/api/BuscaHorarios/001", "https://example.test/api/BuscaItinerarios/001"),
            urls,
        )
    }

    @Test(expected = Exception::class)
    fun `http errors surface as exceptions`() = runTest {
        api("application/json", "oops", HttpStatusCode.InternalServerError).lines()
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.ceturb.CeturbApiTest"`
Expected: FAIL (unresolved `CeturbApi`).

- [ ] **Step 7: Implement the API client**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/ceturb/CeturbApi.kt
package com.cleanmove.data.ceturb

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface CeturbSource {
    suspend fun lines(): List<LineDto>
    suspend fun schedules(code: String): List<ScheduleDto>
    suspend fun itinerary(code: String): List<ItineraryDto>
}

class CeturbApi(
    private val client: HttpClient,
    private val baseUrl: String = BASE_URL,
) : CeturbSource {

    override suspend fun lines(): List<LineDto> =
        client.get("$baseUrl/ConsultaLinha/") { parameter("Tipo_Linha", "T") }.body()

    override suspend fun schedules(code: String): List<ScheduleDto> =
        client.get("$baseUrl/BuscaHorarios/$code").body()

    override suspend fun itinerary(code: String): List<ItineraryDto> =
        client.get("$baseUrl/BuscaItinerarios/$code").body()

    companion object {
        const val BASE_URL = "https://api.onibus.ceturb.es.gov.br/api"
    }
}

fun createCeturbHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true }, ContentType.Any)
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.ceturb.*"`
Expected: all Ceturb tests PASS. (If `http errors surface as exceptions` fails because `expectSuccess` is not set in the test client, set `expectSuccess = true` inside the test's `HttpClient(engine) { ... }` block — the production client already has it.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(data): Ceturb DTOs, mappers and Ktor client" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Room cache and `LineCatalog` repository

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/domain/Repositories.kt`, `data/local/CatalogEntities.kt`, `data/local/CatalogDao.kt`, `data/local/AppDatabase.kt`, `data/repository/LineCatalogRepository.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/LineCatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `CeturbSource`, DTOs and mappers (Task 5); domain models (Task 4).
- Produces:
  - `interface LineCatalog { fun observeLines(query: String): Flow<List<Line>>; suspend fun refreshLinesIfStale(): Result<Unit>; suspend fun loadDetail(code: String): Result<LineDetail> }`
  - `interface FavoritesStore { val favoriteLines: Flow<Set<String>>; suspend fun toggleLine(code: String) }`
  - `@Database AppDatabase { abstract fun catalogDao(): CatalogDao }`
  - `class LineCatalogRepository(source: CeturbSource, dao: CatalogDao, now: () -> Long = System::currentTimeMillis, ttlMillis: Long = 24h) : LineCatalog`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/LineCatalogRepositoryTest.kt
package com.cleanmove.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.ItineraryDto
import com.cleanmove.data.ceturb.LineDto
import com.cleanmove.data.ceturb.ScheduleDto
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.domain.DayType
import com.cleanmove.domain.Line
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private class FakeSource : CeturbSource {
    var fail = false
    var lineCalls = 0
    var detailCalls = 0
    var lines = listOf(LineDto("NOVA PALESTINA / FRADINHOS", "0010"), LineDto("TABUAZEIRO CIRCULAR", "0073"))
    var schedules = listOf(ScheduleDto("TERM", "DIAS ÚTEIS", "06:00", "0010", 1, "A"))
    var itinerary = listOf(ItineraryDto("RUA DO CAJU   ", "0010", "I", 1))

    override suspend fun lines(): List<LineDto> { lineCalls++; if (fail) throw IOException("offline"); return lines }
    override suspend fun schedules(code: String): List<ScheduleDto> { detailCalls++; if (fail) throw IOException("offline"); return schedules }
    override suspend fun itinerary(code: String): List<ItineraryDto> { if (fail) throw IOException("offline"); return itinerary }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineCatalogRepositoryTest {
    private lateinit var db: AppDatabase
    private val source = FakeSource()
    private var clock = 0L
    private lateinit var repo: LineCatalogRepository
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = LineCatalogRepository(source, db.catalogDao(), now = { clock }, ttlMillis = day)
    }

    @After fun tearDown() = db.close()

    @Test fun `first refresh stores lines and a second one inside the TTL does not hit the network`() = runTest {
        assertTrue(repo.refreshLinesIfStale().isSuccess)
        assertTrue(repo.refreshLinesIfStale().isSuccess)
        assertEquals(1, source.lineCalls)
        repo.observeLines("").test {
            assertEquals(listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"), Line("0073", "TABUAZEIRO CIRCULAR")), awaitItem())
            cancel()
        }
    }

    @Test fun `refresh after the TTL fetches again`() = runTest {
        repo.refreshLinesIfStale()
        clock += day + 1
        repo.refreshLinesIfStale()
        assertEquals(2, source.lineCalls)
    }

    @Test fun `search matches name or code case-insensitively`() = runTest {
        repo.refreshLinesIfStale()
        repo.observeLines("tabuaz").test { assertEquals(listOf("0073"), awaitItem().map { it.code }); cancel() }
        repo.observeLines("0010").test { assertEquals(listOf("0010"), awaitItem().map { it.code }); cancel() }
    }

    @Test fun `failed refresh with an empty cache returns failure`() = runTest {
        source.fail = true
        assertTrue(repo.refreshLinesIfStale().isFailure)
    }

    @Test fun `failed refresh keeps the cached lines`() = runTest {
        repo.refreshLinesIfStale()
        clock += day + 1
        source.fail = true
        assertTrue(repo.refreshLinesIfStale().isFailure)
        repo.observeLines("").test { assertEquals(2, awaitItem().size); cancel() }
    }

    @Test fun `loadDetail fetches maps and caches schedules and itinerary`() = runTest {
        repo.refreshLinesIfStale()
        val detail = repo.loadDetail("0010").getOrThrow()
        assertEquals("0010", detail.line.code)
        assertEquals(DayType.WEEKDAY, detail.schedules.single().dayType)
        assertEquals(setOf("A"), detail.schedules.single().notes)
        assertEquals("RUA DO CAJU", detail.itinerary.single().street)
        repo.loadDetail("0010")
        assertEquals(1, source.detailCalls)
    }

    @Test fun `an empty schedule response is cached and not refetched`() = runTest {
        repo.refreshLinesIfStale()
        source.schedules = emptyList(); source.itinerary = emptyList()
        val detail = repo.loadDetail("0010").getOrThrow()
        assertTrue(detail.schedules.isEmpty())
        repo.loadDetail("0010")
        assertEquals(1, source.detailCalls)
    }

    @Test fun `loadDetail serves stale cache when the refetch fails`() = runTest {
        repo.refreshLinesIfStale()
        repo.loadDetail("0010")
        clock += day + 1
        source.fail = true
        assertEquals(1, repo.loadDetail("0010").getOrThrow().schedules.size)
    }

    @Test fun `loadDetail fails when offline with nothing cached`() = runTest {
        repo.refreshLinesIfStale()
        source.fail = true
        assertTrue(repo.loadDetail("0010").isFailure)
    }

    @Test fun `loadDetail fails for an unknown line`() = runTest {
        assertTrue(repo.loadDetail("9999").isFailure)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.LineCatalogRepositoryTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement interfaces, entities, DAO, database**

```kotlin
// app/src/main/kotlin/com/cleanmove/domain/Repositories.kt
package com.cleanmove.domain

import kotlinx.coroutines.flow.Flow

interface LineCatalog {
    fun observeLines(query: String): Flow<List<Line>>
    suspend fun refreshLinesIfStale(): Result<Unit>
    suspend fun loadDetail(code: String): Result<LineDetail>
}

interface FavoritesStore {
    val favoriteLines: Flow<Set<String>>
    suspend fun toggleLine(code: String)
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/local/CatalogEntities.kt
package com.cleanmove.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lines")
data class LineEntity(@PrimaryKey val code: String, val name: String)

@Entity(tableName = "schedules", indices = [Index("lineCode")])
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineCode: String,
    val dayType: String,
    val direction: Int,
    val terminalName: String,
    val minuteOfDay: Int,
    val notes: String,
)

@Entity(tableName = "itinerary", indices = [Index("lineCode")])
data class ItineraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineCode: String,
    val direction: Int,
    val sequence: Int,
    val street: String,
)

@Entity(tableName = "sync_meta")
data class SyncMeta(@PrimaryKey val key: String, val updatedAt: Long)
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/local/CatalogDao.kt
package com.cleanmove.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM lines WHERE name LIKE '%' || :query || '%' OR code LIKE '%' || :query || '%' ORDER BY code")
    fun observeLines(query: String): Flow<List<LineEntity>>

    @Query("SELECT * FROM lines WHERE code = :code")
    suspend fun line(code: String): LineEntity?

    @Query("DELETE FROM lines") suspend fun deleteLines()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(lines: List<LineEntity>)

    @Transaction
    suspend fun replaceLines(lines: List<LineEntity>, meta: SyncMeta) {
        deleteLines(); insertLines(lines); putMeta(meta)
    }

    @Query("SELECT * FROM schedules WHERE lineCode = :code") suspend fun schedules(code: String): List<ScheduleEntity>
    @Query("SELECT * FROM itinerary WHERE lineCode = :code") suspend fun itinerary(code: String): List<ItineraryEntity>
    @Query("DELETE FROM schedules WHERE lineCode = :code") suspend fun deleteSchedules(code: String)
    @Query("DELETE FROM itinerary WHERE lineCode = :code") suspend fun deleteItinerary(code: String)
    @Insert suspend fun insertSchedules(items: List<ScheduleEntity>)
    @Insert suspend fun insertItinerary(items: List<ItineraryEntity>)

    @Transaction
    suspend fun replaceDetail(code: String, schedules: List<ScheduleEntity>, itinerary: List<ItineraryEntity>, meta: SyncMeta) {
        deleteSchedules(code); deleteItinerary(code)
        insertSchedules(schedules); insertItinerary(itinerary)
        putMeta(meta)
    }

    @Query("SELECT updatedAt FROM sync_meta WHERE `key` = :key") suspend fun updatedAt(key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(meta: SyncMeta)
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/data/local/AppDatabase.kt
package com.cleanmove.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LineEntity::class, ScheduleEntity::class, ItineraryEntity::class, SyncMeta::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
```

- [ ] **Step 4: Implement the repository**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/LineCatalogRepository.kt
package com.cleanmove.data.repository

import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.toDepartures
import com.cleanmove.data.ceturb.toDomain
import com.cleanmove.data.ceturb.toSteps
import com.cleanmove.data.local.CatalogDao
import com.cleanmove.data.local.ItineraryEntity
import com.cleanmove.data.local.LineEntity
import com.cleanmove.data.local.ScheduleEntity
import com.cleanmove.data.local.SyncMeta
import com.cleanmove.domain.DayType
import com.cleanmove.domain.ItineraryStep
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.ScheduledDeparture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val LINES_KEY = "lines"
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class LineCatalogRepository(
    private val source: CeturbSource,
    private val dao: CatalogDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DAY_MILLIS,
) : LineCatalog {

    override fun observeLines(query: String): Flow<List<Line>> =
        dao.observeLines(query.trim()).map { rows -> rows.map { Line(it.code, it.name) } }

    override suspend fun refreshLinesIfStale(): Result<Unit> {
        if (isFresh(dao.updatedAt(LINES_KEY))) return Result.success(Unit)
        return catching {
            val lines = source.lines().map { it.toDomain() }.map { LineEntity(it.code, it.name) }
            dao.replaceLines(lines, SyncMeta(LINES_KEY, now()))
        }
    }

    override suspend fun loadDetail(code: String): Result<LineDetail> {
        val line = dao.line(code)?.let { Line(it.code, it.name) }
            ?: return Result.failure(NoSuchElementException("Linha $code não encontrada"))
        val key = "detail:$code"
        val cachedAt = dao.updatedAt(key)
        if (isFresh(cachedAt)) return Result.success(readDetail(line))

        val fetched = catching {
            coroutineScope {
                val schedules = async { source.schedules(code).toDepartures() }
                val itinerary = async { source.itinerary(code).toSteps() }
                dao.replaceDetail(
                    code,
                    schedules.await().map { it.toEntity() },
                    itinerary.await().map { ItineraryEntity(lineCode = it.lineCode.ifEmpty { code }, direction = it.direction, sequence = it.sequence, street = it.street) },
                    SyncMeta(key, now()),
                )
            }
        }
        return when {
            fetched.isSuccess -> Result.success(readDetail(line))
            cachedAt != null -> Result.success(readDetail(line))
            else -> Result.failure(fetched.exceptionOrNull()!!)
        }
    }

    private fun isFresh(updatedAt: Long?): Boolean = updatedAt != null && now() - updatedAt < ttlMillis

    private suspend fun readDetail(line: Line): LineDetail = LineDetail(
        line = line,
        schedules = dao.schedules(line.code).map { it.toDomain() },
        itinerary = dao.itinerary(line.code).map { ItineraryStep(it.lineCode, it.direction, it.sequence, it.street) }
            .sortedWith(compareBy({ it.direction }, { it.sequence })),
    )

    private fun ScheduledDeparture.toEntity() = ScheduleEntity(
        lineCode = lineCode, dayType = dayType.name, direction = direction,
        terminalName = terminalName, minuteOfDay = minuteOfDay, notes = notes.sorted().joinToString(","),
    )

    private fun ScheduleEntity.toDomain() = ScheduledDeparture(
        lineCode = lineCode,
        dayType = runCatching { DayType.valueOf(dayType) }.getOrDefault(DayType.OTHER),
        direction = direction, terminalName = terminalName, minuteOfDay = minuteOfDay,
        notes = notes.split(",").filter { it.isNotEmpty() }.toSet(),
    )

    private suspend fun <T> catching(block: suspend () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.LineCatalogRepositoryTest"`
Expected: 10 tests PASS. Note on the `schedules`/`itinerary` sort: `readDetail` returns schedules in DB insertion order, which `toDepartures()` already sorted.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(data): Room cache and LineCatalog repository with TTL" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Favorites store (DataStore)

**Files:**
- Create: `app/src/main/kotlin/com/cleanmove/data/repository/DataStoreFavorites.kt`
- Test: `app/src/test/kotlin/com/cleanmove/data/repository/DataStoreFavoritesTest.kt`

**Interfaces:**
- Consumes: `FavoritesStore` (Task 6).
- Produces: `class DataStoreFavorites(dataStore: DataStore<Preferences>) : FavoritesStore`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/cleanmove/data/repository/DataStoreFavoritesTest.kt
package com.cleanmove.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreFavoritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun favorites() = DataStoreFavorites(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("fav.preferences_pb") },
    )

    @Test fun `starts empty then toggling adds and removes a line`() = runTest {
        val store = favorites()
        store.favoriteLines.test {
            assertEquals(emptySet<String>(), awaitItem())
            store.toggleLine("0010")
            assertEquals(setOf("0010"), awaitItem())
            store.toggleLine("0073")
            assertEquals(setOf("0010", "0073"), awaitItem())
            store.toggleLine("0010")
            assertEquals(setOf("0073"), awaitItem())
            cancel()
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.DataStoreFavoritesTest"`
Expected: FAIL (unresolved `DataStoreFavorites`).

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/kotlin/com/cleanmove/data/repository/DataStoreFavorites.kt
package com.cleanmove.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cleanmove.domain.FavoritesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreFavorites(private val dataStore: DataStore<Preferences>) : FavoritesStore {
    private val key = stringSetPreferencesKey("favorite_lines")

    override val favoriteLines: Flow<Set<String>> = dataStore.data.map { it[key] ?: emptySet() }

    override suspend fun toggleLine(code: String) {
        dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (code in current) current - code else current + code
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.data.repository.DataStoreFavoritesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(data): DataStore-backed line favorites" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Hilt wiring, ViewModels and the Search / Line detail / Favorites screens

**Files:**
- Create: `di/AppModule.kt`, `ui/search/SearchViewModel.kt`, `ui/search/SearchScreen.kt`, `ui/line/LineDetailViewModel.kt`, `ui/line/LineDetailScreen.kt`, `ui/favorites/FavoritesViewModel.kt`, `ui/favorites/FavoritesScreen.kt`
- Modify: `ui/nav/CleanMoveApp.kt`
- Test: `app/src/test/kotlin/com/cleanmove/ui/MainDispatcherRule.kt`, `ui/FakeCatalog.kt`, `ui/search/SearchViewModelTest.kt`, `ui/line/LineDetailViewModelTest.kt`, `ui/favorites/FavoritesViewModelTest.kt`

**Interfaces:**
- Consumes: `LineCatalog`, `FavoritesStore` (Task 6), `LineCatalogRepository`, `DataStoreFavorites`, `AppDatabase`, `createCeturbHttpClient`, `CeturbApi`, `Tab`/`CleanMoveTheme`/`LiveColor`/`ScheduledColor` (Task 3).
- Produces:
  - `SearchUiState(query, lines, loading, error)`; `SearchViewModel(catalog: LineCatalog)` with `state: StateFlow<SearchUiState>`, `onQueryChange(String)`, `retry()`.
  - `sealed interface LineDetailUiState { Loading; Error; Loaded(detail: LineDetail, isFavorite: Boolean) }`; `LineDetailViewModel(savedStateHandle, catalog, favorites)` reading arg `"code"`, with `state`, `toggleFavorite()`, `retry()`.
  - `FavoritesViewModel(catalog, favorites)` with `state: StateFlow<List<Line>>`, `remove(code)`.
  - Nav route `"line/{code}"`.

- [ ] **Step 1: Write shared test helpers**

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/MainDispatcherRule.kt
package com.cleanmove.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/FakeCatalog.kt
package com.cleanmove.ui

import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCatalog(
    initialLines: List<Line> = emptyList(),
    var refreshResult: Result<Unit> = Result.success(Unit),
    var detailResult: Result<LineDetail> = Result.failure(IllegalStateException("not set")),
) : LineCatalog {
    val lines = MutableStateFlow(initialLines)
    var refreshCalls = 0

    override fun observeLines(query: String): Flow<List<Line>> =
        lines.map { all -> all.filter { it.name.contains(query, true) || it.code.contains(query, true) } }

    override suspend fun refreshLinesIfStale(): Result<Unit> { refreshCalls++; return refreshResult }
    override suspend fun loadDetail(code: String): Result<LineDetail> = detailResult
}

class FakeFavorites(initial: Set<String> = emptySet()) : FavoritesStore {
    val state = MutableStateFlow(initial)
    override val favoriteLines: Flow<Set<String>> = state
    override suspend fun toggleLine(code: String) { state.value = if (code in state.value) state.value - code else state.value + code }
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/search/SearchViewModelTest.kt
package com.cleanmove.ui.search

import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val lines = listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"), Line("0073", "TABUAZEIRO CIRCULAR"))

    @Test fun `empty cache and failed refresh shows error without loading`() = runTest {
        val vm = SearchViewModel(FakeCatalog(refreshResult = Result.failure(IOException("offline"))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertTrue(s.error); assertFalse(s.loading); assertTrue(s.lines.isEmpty())
            cancel()
        }
    }

    @Test fun `cached lines are shown without error even when refresh fails`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines, Result.failure(IOException("offline"))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(2, s.lines.size); assertFalse(s.error); assertFalse(s.loading)
            cancel()
        }
    }

    @Test fun `typing filters the list`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines))
        vm.state.test {
            vm.onQueryChange("tabuaz")
            assertEquals(listOf("0073"), expectMostRecentItem().lines.map { it.code })
            cancel()
        }
    }

    @Test fun `retry refreshes again and clears the error on success`() = runTest {
        val catalog = FakeCatalog(emptyList(), Result.failure(IOException()))
        val vm = SearchViewModel(catalog)
        vm.state.test {
            assertTrue(expectMostRecentItem().error)
            catalog.refreshResult = Result.success(Unit)
            catalog.lines.value = lines
            vm.retry()
            val s = expectMostRecentItem()
            assertFalse(s.error); assertEquals(2, s.lines.size)
            cancel()
        }
        assertEquals(2, catalog.refreshCalls)
    }
}
```

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/line/LineDetailViewModelTest.kt
package com.cleanmove.ui.line

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineDetail
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.FakeFavorites
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class LineDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val detail = LineDetail(Line("0010", "NOVA PALESTINA / FRADINHOS"), emptyList(), emptyList())
    private fun vm(catalog: FakeCatalog, favorites: FakeFavorites = FakeFavorites()) =
        LineDetailViewModel(SavedStateHandle(mapOf("code" to "0010")), catalog, favorites)

    @Test fun `loads detail and reflects favorite status`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)), FakeFavorites(setOf("0010")))
        vm.state.test {
            val s = expectMostRecentItem() as LineDetailUiState.Loaded
            assertEquals(detail, s.detail); assertTrue(s.isFavorite)
            cancel()
        }
    }

    @Test fun `empty schedules still load successfully`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)))
        vm.state.test {
            assertTrue((expectMostRecentItem() as LineDetailUiState.Loaded).detail.schedules.isEmpty())
            cancel()
        }
    }

    @Test fun `failure becomes an error state and retry recovers`() = runTest {
        val catalog = FakeCatalog(detailResult = Result.failure(IOException("offline")))
        val vm = vm(catalog)
        vm.state.test {
            assertEquals(LineDetailUiState.Error, expectMostRecentItem())
            catalog.detailResult = Result.success(detail)
            vm.retry()
            assertTrue(expectMostRecentItem() is LineDetailUiState.Loaded)
            cancel()
        }
    }

    @Test fun `toggleFavorite flips the favorite flag`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)))
        vm.state.test {
            assertFalse((expectMostRecentItem() as LineDetailUiState.Loaded).isFavorite)
            vm.toggleFavorite()
            assertTrue((expectMostRecentItem() as LineDetailUiState.Loaded).isFavorite)
            cancel()
        }
    }
}
```

```kotlin
// app/src/test/kotlin/com/cleanmove/ui/favorites/FavoritesViewModelTest.kt
package com.cleanmove.ui.favorites

import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.FakeFavorites
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoritesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val lines = listOf(Line("0010", "A"), Line("0073", "B"), Line("0099", "C"))

    @Test fun `shows only favorite lines that exist in the catalog`() = runTest {
        val vm = FavoritesViewModel(FakeCatalog(lines), FakeFavorites(setOf("0073", "9999")))
        vm.state.test {
            assertEquals(listOf("0073"), expectMostRecentItem().map { it.code })
            cancel()
        }
    }

    @Test fun `remove drops a favorite`() = runTest {
        val vm = FavoritesViewModel(FakeCatalog(lines), FakeFavorites(setOf("0010", "0073")))
        vm.state.test {
            vm.remove("0010")
            assertEquals(listOf("0073"), expectMostRecentItem().map { it.code })
            cancel()
        }
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.*"`
Expected: FAIL (unresolved ViewModels).

- [ ] **Step 4: Implement the ViewModels**

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/search/SearchViewModel.kt
package com.cleanmove.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val lines: List<Line> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

private enum class RefreshStatus { Loading, Ready, Failed }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val catalog: LineCatalog) : ViewModel() {
    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(RefreshStatus.Loading)

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.flatMapLatest { catalog.observeLines(it) },
        status,
    ) { q, lines, s ->
        SearchUiState(
            query = q,
            lines = lines,
            loading = s == RefreshStatus.Loading && lines.isEmpty(),
            error = s == RefreshStatus.Failed && lines.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init { refresh() }

    fun onQueryChange(value: String) { query.value = value }

    fun retry() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            status.value = RefreshStatus.Loading
            status.value = if (catalog.refreshLinesIfStale().isSuccess) RefreshStatus.Ready else RefreshStatus.Failed
        }
    }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/line/LineDetailViewModel.kt
package com.cleanmove.ui.line

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LineDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LineDetailUiState {
    data object Loading : LineDetailUiState
    data object Error : LineDetailUiState
    data class Loaded(val detail: LineDetail, val isFavorite: Boolean) : LineDetailUiState
}

@HiltViewModel
class LineDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: LineCatalog,
    private val favorites: FavoritesStore,
) : ViewModel() {
    private val code: String = checkNotNull(savedStateHandle["code"])
    private val detail = MutableStateFlow<Result<LineDetail>?>(null)

    val state: StateFlow<LineDetailUiState> = combine(detail, favorites.favoriteLines) { result, favs ->
        when {
            result == null -> LineDetailUiState.Loading
            result.isFailure -> LineDetailUiState.Error
            else -> LineDetailUiState.Loaded(result.getOrThrow(), code in favs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LineDetailUiState.Loading)

    init { load() }

    fun retry() = load()

    fun toggleFavorite() { viewModelScope.launch { favorites.toggleLine(code) } }

    private fun load() {
        viewModelScope.launch {
            detail.value = null
            detail.value = catalog.loadDetail(code)
        }
    }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/favorites/FavoritesViewModel.kt
package com.cleanmove.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    catalog: LineCatalog,
    private val favorites: FavoritesStore,
) : ViewModel() {
    val state: StateFlow<List<Line>> = combine(catalog.observeLines(""), favorites.favoriteLines) { lines, favs ->
        lines.filter { it.code in favs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(code: String) { viewModelScope.launch { favorites.toggleLine(code) } }
}
```

- [ ] **Step 5: Run to verify ViewModel tests pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanmove.ui.*"`
Expected: all ViewModel tests PASS (plus BottomBarTest).

- [ ] **Step 6: Implement Hilt module**

```kotlin
// app/src/main/kotlin/com/cleanmove/di/AppModule.kt
package com.cleanmove.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.cleanmove.data.ceturb.CeturbApi
import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.createCeturbHttpClient
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.data.local.CatalogDao
import com.cleanmove.data.repository.DataStoreFavorites
import com.cleanmove.data.repository.LineCatalogRepository
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.LineCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun ceturbSource(): CeturbSource = CeturbApi(createCeturbHttpClient())

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "clean-move.db").build()

    @Provides fun catalogDao(db: AppDatabase): CatalogDao = db.catalogDao()

    @Provides @Singleton
    fun lineCatalog(source: CeturbSource, dao: CatalogDao): LineCatalog = LineCatalogRepository(source, dao)

    @Provides @Singleton
    fun favorites(@ApplicationContext context: Context): FavoritesStore = DataStoreFavorites(context.favoritesDataStore)
}
```

- [ ] **Step 7: Implement the screens**

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/search/SearchScreen.kt
package com.cleanmove.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.Line

@Composable
fun SearchScreen(onLineClick: (String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Buscar linha (número ou nome)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        )
        when {
            state.loading -> Centered { CircularProgressIndicator() }
            state.error -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Não foi possível carregar as linhas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::retry) { Text("Tentar novamente") }
                }
            }
            state.lines.isEmpty() -> Centered { Text("Nenhuma linha encontrada", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.lines, key = { it.code }) { LineRow(it, onLineClick) }
            }
        }
    }
}

@Composable
fun LineRow(line: Line, onClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onClick(line.code) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(line.code, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text(line.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/line/LineDetailScreen.kt
package com.cleanmove.ui.line

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.domain.DayType
import com.cleanmove.domain.LineDetail
import com.cleanmove.domain.ScheduledDeparture
import com.cleanmove.ui.theme.ScheduledColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineDetailScreen(onBack: () -> Unit, viewModel: LineDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded = state as? LineDetailUiState.Loaded
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.detail?.line?.name ?: "Linha") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = {
                    if (loaded != null) IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (loaded.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (loaded.isFavorite) "Remover dos favoritos" else "Favoritar",
                            tint = if (loaded.isFavorite) ScheduledColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                LineDetailUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                LineDetailUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Não foi possível carregar a linha.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = viewModel::retry) { Text("Tentar novamente") }
                    }
                }
                is LineDetailUiState.Loaded -> LineDetailContent(s.detail)
            }
        }
    }
}

private fun DayType.label() = when (this) {
    DayType.WEEKDAY -> "Dias úteis"
    DayType.SATURDAY -> "Sábado"
    DayType.SUNDAY_HOLIDAY -> "Domingos e feriados"
    DayType.ATYPICAL -> "Atípicos entre feriados"
    DayType.OTHER -> "Outros"
}

fun formatMinuteOfDay(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LineDetailContent(detail: LineDetail) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Horários de saída", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (detail.schedules.isEmpty()) {
            item { Text("Sem horários programados disponíveis.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            val groups = detail.schedules.groupBy { it.dayType to it.direction }.toList()
                .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            items(groups, key = { "${it.first.first}-${it.first.second}" }) { (key, departures) ->
                ScheduleGroup(key.first, departures)
            }
        }
        item { Text("Itinerário", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
        if (detail.itinerary.isEmpty()) {
            item { Text("Itinerário indisponível.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            val byDirection = detail.itinerary.groupBy { it.direction }.toSortedMap()
            byDirection.forEach { (direction, steps) ->
                item(key = "dir-$direction") {
                    Text("Sentido $direction", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(steps, key = { "step-${it.direction}-${it.sequence}" }) { Text(it.street, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Box(Modifier.padding(bottom = 24.dp)) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScheduleGroup(dayType: DayType, departures: List<ScheduledDeparture>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dayType.label(), style = MaterialTheme.typography.labelLarge)
            Text("de ${departures.first().terminalName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            departures.forEach { Text(formatMinuteOfDay(it.minuteOfDay), color = ScheduledColor) }
        }
    }
}
```

```kotlin
// app/src/main/kotlin/com/cleanmove/ui/favorites/FavoritesScreen.kt
package com.cleanmove.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanmove.ui.search.LineRow
import com.cleanmove.ui.theme.ScheduledColor

@Composable
fun FavoritesScreen(onLineClick: (String) -> Unit, viewModel: FavoritesViewModel = hiltViewModel()) {
    val lines by viewModel.state.collectAsStateWithLifecycle()
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhum favorito ainda.\nToque na estrela de uma linha para salvá-la.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines, key = { it.code }) { line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LineRow(line, onLineClick) }
                    IconButton(onClick = { viewModel.remove(line.code) }) {
                        Icon(Icons.Filled.Star, contentDescription = "Remover dos favoritos", tint = ScheduledColor)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8: Wire the screens into navigation**

Replace the `NavHost` block in `ui/nav/CleanMoveApp.kt` (and fix imports: remove `androidx.compose.material3.Text`, add `androidx.navigation.NavType`, `androidx.navigation.navArgument`, `com.cleanmove.ui.favorites.FavoritesScreen`, `com.cleanmove.ui.line.LineDetailScreen`, `com.cleanmove.ui.search.SearchScreen`) with:

```kotlin
            NavHost(nav, startDestination = Tab.Map.route, modifier = Modifier.padding(padding)) {
                composable(Tab.Map.route) { MapPlaceholderScreen() }
                composable(Tab.Search.route) { SearchScreen(onLineClick = { nav.navigate("line/$it") }) }
                composable(Tab.Favorites.route) { FavoritesScreen(onLineClick = { nav.navigate("line/$it") }) }
                composable("line/{code}", arguments = listOf(navArgument("code") { type = NavType.StringType })) {
                    LineDetailScreen(onBack = { nav.popBackStack() })
                }
            }
```

- [ ] **Step 9: Run the full test suite and assemble**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: all unit tests PASS; BUILD SUCCESSFUL.

- [ ] **Step 10: Manual end-to-end check on the emulator**

Create an AVD in Android Studio (Device Manager → Pixel, API 35 x86_64 image), start it, then run: `.\gradlew.bat :app:installDebug` and launch **Clean Move**. Verify: Buscar tab lists Transcol lines; typing `tabuaz` filters; opening line `0010` shows departure times grouped by day and the itinerary; the star adds it to Favoritos; turning on airplane mode and reopening a visited line still shows cached data; opening an unvisited line while offline shows the retry state.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: line search, line detail and favorites screens" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Handoff to Plan 2

When Task 8 passes, `docs/spike-findings.md` (Task 1) tells us the real Geocontrol shapes, the line-code mapping and the R1/R2 verdicts. Plan 2 is then written from the spec plus those findings and covers: Geocontrol client + DTOs + fixtures tests, stop catalog (Room, area queries), live arrivals through `ArrivalMerger`, scheduled-time derivation per stop (or the line-level fallback), Google Maps screen with dark style and live vehicles, stop favorites, About screen with data-owner credits, polish (empty/error states), dependency upgrade to latest stable with `targetSdk` bump, and the launch checklist.
