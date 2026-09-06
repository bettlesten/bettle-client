# Bettle Client

A crimson-themed Minecraft launcher and client framework: dynamic version
management straight from Mojang's manifest, full Microsoft OAuth2 login, a
complete vanilla install/launch engine, a frameless JavaFX dashboard, and a
companion Fabric HUD mod.

---

## Shipping it to other people

The distributable is **one file**: `BettleClient-Setup-1.0.0.exe`. Someone
downloads it, double-clicks, and gets the launcher with its own private Java
runtime. No JDK, no JRE, no Minecraft install, nothing else first — and
**no UAC prompt**, because it installs per-user into
`%LOCALAPPDATA%\Programs\BettleClient`.

It is a real NSIS installer with its own crimson branding, not a Windows
Installer (`.msi`) wrapper — the same shape as the Minecraft client installers
people are used to.

### The easy way: let GitHub build it

You do not need a Windows machine. Push this repo to GitHub and either run
**Actions → Build Windows Installer → Run workflow**, or tag a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

`.github/workflows/build-windows-exe.yml` builds on a `windows-latest` runner
and attaches `BettleClient-Setup-1.0.0.exe` to a GitHub Release. That release
page is the link you share.

### The local way: build it on Windows

```powershell
.\packaging\windows\build-exe.ps1
```

or double-click `packaging\windows\build-exe.bat`. Output lands in
`dist\BettleClient-Setup-1.0.0.exe`.

| Tool | Why | Where |
|---|---|---|
| **Liberica Full JDK 21** | jpackage, plus the JavaFX jmods | <https://bell-sw.com/pages/downloads/> — pick **Full JDK** |
| **Maven 3.8+** | builds the launcher jar | <https://maven.apache.org/download.cgi> |
| **NSIS 3.x** | builds the installer `.exe` | `choco install nsis -y` |
| Gradle *(optional)* | builds the HUD mod to embed | `choco install gradle -y` |

Useful switches:

```powershell
.\packaging\windows\build-exe.ps1 -NoInstaller       # stop after the app image, no NSIS needed
.\packaging\windows\build-exe.ps1 -SkipMod           # launcher only, skip the Gradle build
.\packaging\windows\build-exe.ps1 -AppVersion 1.1.0
```

### The pipeline

```
bettle-mod/  --gradle-->  bettleclient-1.0.0.jar
                              |
                              v  embedded as a classpath resource
src/main/resources/net/bettleclient/bundled/bettle-hud-mod.jar
                              |
                              v  mvn -P jpackage package
                    target/BettleClient-1.0.0.jar
                              |
                              v  jpackage --type app-image
              build/app/Bettle Client/  (launcher.exe + runtime/)
                              |
                              v  makensis installer.nsi
              dist/BettleClient-Setup-1.0.0.exe
```

Two deliberate choices here. **jpackage stops at `app-image`**, which is why
WiX is not needed anywhere — NSIS does the packaging, and that is what buys the
custom installer UI. And the **`jpackage` Maven profile** flips the JavaFX
dependencies to `provided`, so JavaFX is linked into the runtime image instead
of being duplicated inside the jar. A plain `mvn package` keeps JavaFX shaded
in, which is what makes `java -jar` work on any ordinary JDK 17+.

### Two sizes to choose from

**Self-contained (default)** — roughly 35–45 MB. Everything is inside the exe,
so it installs with no network at all. One file to host.

**Web stub** — roughly 1–2 MB, the shape most client installers use. The stub
downloads a payload zip at install time:

```powershell
.\packaging\windows\build-exe.ps1 -Web `
    -PayloadUrl https://github.com/you/bettle/releases/download/v1.0.0/BettleClient-payload-1.0.0.zip
```

That produces the stub *and* the zip. Upload the zip to exactly that URL, then
publish the stub. The stub is inert until the zip is live. It uses PowerShell
for the download and the unzip rather than an NSIS download plugin, so the
script builds with a stock NSIS install and speaks modern TLS.

### What the installer does

Welcome page → install folder → progress → finish, with a *Launch now* button
and an optional desktop shortcut. It registers properly in **Add/Remove
Programs** (name, version, publisher, icon, estimated size, uninstall string),
creates Start Menu entries, refuses to install over a running copy, and
silently removes an older build before upgrading.

Uninstalling removes the program and shortcuts but **leaves
`%APPDATA%\.bettleclient` alone** — that holds downloaded versions, instances
and worlds. It offers to delete them and defaults to *No*; wiping someone's
saves because they uninstalled a launcher would be indefensible.

### Two caveats worth knowing

- **SmartScreen.** An unsigned installer triggers "Windows protected your PC"
  on first run; users click *More info → Run anyway*. The only real fix is an
  Authenticode code-signing certificate (~$200–400/year from Sectigo, DigiCert
  and others), after which you add a `signtool sign` step to the build script.
- **jpackage is not a cross-compiler.** It only builds for the OS it runs on, so
  a Windows `.exe` must be built on Windows — hence the CI workflow. NSIS
  itself *does* cross-compile from Linux, but the app-image it packages does
  not.

---

## Repository layout

```
bettle-client/
├── pom.xml                                     Maven build for the launcher
├── src/main/java/net/bettleclient/
│   ├── Main.java                               entry point / CLI bootstrap
│   ├── auth/MicrosoftAuthenticator.java        MSA device-code OAuth2 chain
│   ├── auth/MinecraftSession.java              verified session credentials
│   ├── version/VersionManager.java             manifest_v2 fetch + filtering
│   ├── version/VersionEntry.java               one manifest entry
│   ├── launcher/MinecraftLauncher.java         install + launch engine
│   ├── launcher/LaunchOptions.java             per-launch settings
│   ├── launcher/ProgressListener.java          progress callbacks
│   ├── install/FabricInstaller.java            writes the Fabric profile JSON
│   ├── install/ModInstaller.java               embeds HUD mod + fetches Fabric API
│   ├── install/ModLoader.java                  Vanilla / Fabric profile choice
│   ├── config/LauncherConfig.java              persisted settings
│   ├── ui/BettleUI.java                        JavaFX dashboard
│   └── util/{HttpUtil,OperatingSystem,RuleEvaluator}.java
├── src/main/resources/net/bettleclient/ui/theme.css
├── src/main/resources/net/bettleclient/bundled/   HUD mod jar embedded at build time
├── packaging/windows/
│   ├── build-exe.ps1                           the installer build
│   ├── build-exe.bat                           double-clickable wrapper
│   ├── installer.nsi                           NSIS installer definition
│   ├── BettleClient.ico                        app / installer icon
│   ├── welcome.bmp                             installer sidebar art (164x314)
│   └── header.bmp                              installer header art (150x57)
├── .github/workflows/build-windows-exe.yml     builds the .exe on CI
└── bettle-mod/                                 Fabric client mod (Gradle + Loom)
    ├── build.gradle
    ├── gradle.properties
    ├── settings.gradle
    └── src/main/java/net/bettleclient/mod/BettleHudOverlay.java
```

### Why two build systems

Fabric Loom is a **Gradle** plugin with no Maven equivalent — it has to
decompile and remap Minecraft before compiling against it. So the launcher is
built with Maven (`pom.xml`) and the mod with Gradle + Loom
(`bettle-mod/build.gradle`). They are independent artifacts: the launcher does
not depend on the mod, and the mod is installed into a Fabric instance like
any other mod.

---

## Building

### Launcher

```bash
mvn clean package
java -jar target/BettleClient-1.0.0.jar
```

The shade plugin produces a self-contained jar. `Main` deliberately does not
extend `Application`, which is what lets `java -jar` work without putting
JavaFX on the module path.

Run from source during development:

```bash
mvn javafx:run
```

Headless smoke test of the version engine (no GUI, hits Mojang's real manifest):

```bash
java -jar target/BettleClient-1.0.0.jar --versions
java -jar target/BettleClient-1.0.0.jar --info
```

### Fabric mod

```bash
cd bettle-mod
./gradlew build          # -> build/libs/bettleclient-1.0.0.jar
./gradlew runClient      # dev client with the mod loaded
```

Retarget a different Minecraft version by editing three lines in
`gradle.properties` (`minecraft_version`, `yarn_mappings`, `fabric_version`);
current values are on <https://fabricmc.net/develop>.

---

## Feature notes

### Dynamic version fetching

`VersionManager` reads
`https://launchermeta.mojang.com/mc/game/version_manifest_v2.json` (with the
`piston-meta` mirror as fallback) and caches it for six hours so the dropdown
still populates offline.

No version list is hardcoded. The supported window is a *predicate*: an
inclusive lower bound of `1.20.1` and an inclusive major-version ceiling of
`26`. Version ids are compared numerically component by component, which
handles both today's `1.MAJOR.MINOR` scheme and a bare `MAJOR.MINOR` scheme, so
a future `1.22`, `2.0` or `26.4` release appears the next time the manifest
refreshes without a code change.

### Microsoft authentication

`MicrosoftAuthenticator` runs the OAuth2 **device code** grant through RaphiMC
MinecraftAuth:

```
MSA device code -> MSA token -> Xbox Live (XBL) -> XSTS -> Minecraft services -> profile
```

The result carries a real Minecraft services access token, so the game can
complete the session-server handshake and join official online-mode servers.
The whole session (including the MSA refresh token) is written to
`session.json` with `0600` permissions where the filesystem supports it, and
refreshed silently on subsequent launches. The XUID needed for 1.19+ chat
signing is read from the `xuid` claim of the access-token JWT rather than from
MinecraftAuth's internal token chain, which keeps the launcher working across
library major versions.

### Launch engine

`MinecraftLauncher` handles version JSON resolution (including `inheritsFrom`
chains, so Fabric/Quilt/Forge profiles work), SHA-1-verified downloads with
retry and atomic replace, platform rule evaluation, both the pre-1.17
`classifiers`+`natives` layout and the modern `natives-<os>` classpath jars,
asset index and object downloads in parallel, legacy/virtual asset unpacking,
placeholder substitution for every `${...}` token Mojang defines, and the
`ProcessBuilder` spawn with an output pump. Access tokens are redacted from the
command line written to the console.

Quick-connect uses `--quickPlayMultiplayer` on 1.20+ and falls back to
`--server`/`--port` on older profiles.

### Fabric and the HUD mod

Selecting **Fabric + Bettle HUD** in the Profile dropdown does three things
before the game starts, each idempotent so repeat launches are cheap:

1. `FabricInstaller` asks `meta.fabricmc.net` for the newest stable loader for
   the chosen Minecraft version and writes its profile JSON to
   `versions/fabric-loader-<loader>-<mc>/`. That file *is* the installation —
   the launch engine already resolves its `inheritsFrom` chain and the Fabric
   libraries that carry a `url` base instead of a `downloads` block.
2. `ModInstaller` copies the embedded HUD mod jar into the instance's `mods/`
   folder, refreshing it whenever the launcher is upgraded.
3. It then fetches the Fabric API build matching that exact Minecraft version
   from Modrinth, and deletes any Fabric API jar left over from a different
   version — two copies in `mods/` stop Fabric from starting, which is the
   usual failure after switching versions.

Fabric instances get their own game directory (`instances/<version>-fabric`)
so a modded profile never contaminates a vanilla one. When a Minecraft version
is too new for Fabric, the installer says so and suggests Vanilla rather than
failing opaquely; when Fabric API is missing, the game still launches, just
without the HUD.

### Data directories

| Platform | Launcher root |
|---|---|
| Windows | `%APPDATA%\.bettleclient` |
| macOS | `~/Library/Application Support/bettleclient` |
| Linux | `~/.bettleclient` |

```
<root>/versions/<id>/<id>.{json,jar}
<root>/versions/<id>/natives/
<root>/libraries/<maven path>
<root>/assets/{indexes,objects}/
<root>/instances/<id>/           per-version game directory
<root>/{config,session}.json
```

### HUD mod key bindings

| Key | Action |
|---|---|
| `V` | Toggle the ToggleSprint helper |
| `]` | Show/hide the HUD panel |
| `[` | Cycle the HUD between screen corners |

CPS is sampled once per rendered frame rather than once per game tick, so it
resolves clicks far faster than 20 Hz without needing a mixin on the mouse
handler.

---

## Compatibility caveats

- **`HudRenderCallback` signature.** The mod targets Minecraft 1.20.1–1.21.1.
  On 1.21.2+, Fabric API changed the callback's second parameter from
  `float tickDelta` to `RenderTickCounter`; only the signature of
  `onHudRender` needs to change, and `HudLayerRegistrationCallback` is the
  forward-looking replacement. The method body is unchanged either way.
- **Dependency versions** in `pom.xml` and `gradle.properties` are pinned to
  known-good releases at the time of writing. Bump them as upstream moves;
  `net.raphimc:MinecraftAuth` resolves from `maven.lenni0451.net` as well as
  Maven Central.
