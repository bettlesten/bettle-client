<#
.SYNOPSIS
    Builds Bettle Client into a single branded Windows installer .exe.

.DESCRIPTION
    Produces dist\BettleClient-Setup-<version>.exe - one file that end users
    download and double-click. It carries the launcher and its own trimmed Java
    runtime, so nobody needs Java, and it installs per-user so Windows never
    shows a UAC prompt.

    Pipeline:
      1. Gradle   bettle-mod                -> bettleclient-<v>.jar
      2. copy     into launcher resources   -> bundled\bettle-hud-mod.jar
      3. Maven    -P jpackage package       -> BettleClient-<v>.jar
      4. jpackage --type app-image          -> build\app\Bettle Client\
      5. makensis installer.nsi             -> dist\BettleClient-Setup-<v>.exe

    Step 4 uses app-image rather than jpackage's own installer types, so WiX is
    not needed at all. NSIS does the packaging, which is what gives the
    installer its own look instead of a Windows Installer wrapper.

.PARAMETER AppVersion
    Version stamped on the installer and the app. Default 1.0.0.

.PARAMETER Web
    Build a small downloader stub plus a separate payload zip, instead of one
    self-contained installer. The stub is ~1-2 MB; host the zip anywhere (a
    GitHub Release asset works) and pass its URL with -PayloadUrl.

.PARAMETER PayloadUrl
    HTTPS URL the -Web stub downloads the payload zip from. Required with -Web.

.PARAMETER NoInstaller
    Stop after the app-image. Useful for testing the app without NSIS present.

.PARAMETER SkipMod
    Skip the Gradle mod build. The launcher still works; the Fabric profile
    just installs Fabric without the embedded HUD mod.

.EXAMPLE
    .\build-exe.ps1
    .\build-exe.ps1 -AppVersion 1.1.0
    .\build-exe.ps1 -NoInstaller
    .\build-exe.ps1 -Web -PayloadUrl https://github.com/you/bettle/releases/download/v1.0.0/payload.zip

.NOTES
    Requirements:
      * JDK 17+ with jpackage. A *Full* JDK that bundles JavaFX (Liberica Full
        JDK or Azul Zulu FX) is strongly recommended; the script detects it and
        links JavaFX into the runtime image rather than shading it into the jar.
        https://bell-sw.com/pages/downloads/  -> "Full JDK"
      * Maven 3.8+
      * NSIS 3.x            choco install nsis -y
      * Gradle (optional)   choco install gradle -y
#>

[CmdletBinding()]
param(
    [string] $AppVersion = "1.0.0",
    [switch] $Web,
    [string] $PayloadUrl = "",
    [switch] $NoInstaller,
    [switch] $SkipMod
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

$AppName   = "Bettle Client"
$MainClass = "net.bettleclient.Main"
$Vendor    = "Bettle Client"

# JDK modules the app needs. Listing them explicitly beats letting jpackage
# guess via jdeps; jdk.crypto.ec in particular is required or TLS handshakes to
# Mojang's CDN fail at runtime with a confusing handshake_failure.
$BaseModules = @(
    "java.base", "java.datatransfer", "java.desktop", "java.logging",
    "java.management", "java.naming", "java.net.http", "java.prefs",
    "java.scripting", "java.xml",
    "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.management",
    "jdk.unsupported", "jdk.zipfs"
)
$JavaFxModules = @("javafx.base", "javafx.graphics", "javafx.controls", "javafx.fxml", "javafx.swing")

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

function Write-Step  ([string] $Message) { Write-Host "`n==> $Message" -ForegroundColor Red }
function Write-Info  ([string] $Message) { Write-Host "    $Message" -ForegroundColor Gray }
function Write-Ok    ([string] $Message) { Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn2 ([string] $Message) { Write-Host "    $Message" -ForegroundColor Yellow }

function Resolve-Tool ([string] $Name, [string[]] $ExtraPaths = @()) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($p in $ExtraPaths) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Invoke-Checked ([string] $Executable, [string[]] $Arguments, [string] $What) {
    Write-Info "$Executable $($Arguments -join ' ')"
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

function Format-Size ([long] $Bytes) {
    if ($Bytes -ge 1MB) { return "$([math]::Round($Bytes / 1MB, 1)) MB" }
    return "$([math]::Round($Bytes / 1KB, 0)) KB"
}

# --------------------------------------------------------------------------
# Locate the project root (this script lives in packaging\windows)
# --------------------------------------------------------------------------

$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot     = (Resolve-Path (Join-Path $ScriptDirectory "..\..")).Path
Set-Location $ProjectRoot

Write-Host ""
Write-Host "  BETTLE CLIENT - Windows installer build" -ForegroundColor Red
Write-Host "  project root: $ProjectRoot" -ForegroundColor DarkGray
Write-Host "  version:      $AppVersion" -ForegroundColor DarkGray
Write-Host "  mode:         $(if ($Web) { 'web stub + payload zip' } else { 'self-contained installer' })" -ForegroundColor DarkGray
Write-Host ""

if ($Web -and -not $PayloadUrl) {
    throw "-Web needs -PayloadUrl, the HTTPS address the stub will download the payload zip from."
}

# --------------------------------------------------------------------------
# 0. Toolchain
# --------------------------------------------------------------------------

Write-Step "Checking the toolchain"

$JPackage = Resolve-Tool "jpackage"
if (-not $JPackage) {
    throw "jpackage was not found. Install a JDK 17 or newer, set JAVA_HOME, or put its bin folder on PATH."
}
Write-Ok "jpackage: $JPackage"

$JavaHome = $env:JAVA_HOME
if (-not $JavaHome) {
    $JavaHome = Split-Path -Parent (Split-Path -Parent $JPackage)
    Write-Info "JAVA_HOME was not set; inferred $JavaHome"
}

$HasJavaFxJmods = Test-Path (Join-Path $JavaHome "jmods\javafx.controls.jmod")
if ($HasJavaFxJmods) {
    Write-Ok "Full JDK detected - JavaFX will be linked into the runtime image."
} else {
    Write-Warn2 "This JDK has no JavaFX jmods, so JavaFX will be shaded into the jar instead."
    Write-Warn2 "That works, but a Full JDK from https://bell-sw.com/pages/downloads/ gives a"
    Write-Warn2 "smaller and more reliable result. Install one and re-run to switch automatically."
}

$Maven = Resolve-Tool "mvn"
if (-not $Maven) { $Maven = Resolve-Tool "mvn.cmd" }
if (-not $Maven) {
    throw "Maven was not found on PATH. Install Maven 3.8+ from https://maven.apache.org/download.cgi"
}
Write-Ok "maven: $Maven"

$MakeNsis = $null
if (-not $NoInstaller) {
    $MakeNsis = Resolve-Tool "makensis" @(
        "${env:ProgramFiles(x86)}\NSIS\makensis.exe",
        "$env:ProgramFiles\NSIS\makensis.exe"
    )
    if (-not $MakeNsis) {
        throw @"
NSIS was not found, and it is what turns the app-image into the installer .exe.

  Install it with:   choco install nsis -y
  Or download:       https://nsis.sourceforge.io/Download

Or re-run with -NoInstaller to stop after the app-image.
"@
    }
    Write-Ok "makensis: $MakeNsis"
}

# --------------------------------------------------------------------------
# 1. Fabric HUD mod
# --------------------------------------------------------------------------

$BundleDirectory = Join-Path $ProjectRoot "src\main\resources\net\bettleclient\bundled"
$BundledModPath  = Join-Path $BundleDirectory "bettle-hud-mod.jar"

if ($SkipMod) {
    Write-Step "Skipping the mod build (-SkipMod)"
} else {
    Write-Step "Building the Fabric HUD mod"

    $ModDirectory  = Join-Path $ProjectRoot "bettle-mod"
    $GradleWrapper = Join-Path $ModDirectory "gradlew.bat"
    $GradleCommand = $null

    if (Test-Path $GradleWrapper) {
        $GradleCommand = $GradleWrapper
    } else {
        $Gradle = Get-Command "gradle" -ErrorAction SilentlyContinue
        if ($Gradle) {
            $GradleCommand = $Gradle.Source
            Write-Info "No Gradle wrapper present; using the Gradle on PATH."
        }
    }

    if (-not $GradleCommand) {
        Write-Warn2 "Gradle was not found, so the HUD mod will not be embedded."
        Write-Warn2 "Install it (choco install gradle -y) or run 'gradle wrapper' in bettle-mod\."
        Write-Warn2 "The launcher still builds; the Fabric profile just installs Fabric without the HUD."
    } else {
        Push-Location $ModDirectory
        try {
            Invoke-Checked $GradleCommand @("build", "--no-daemon") "Gradle mod build"
        } finally {
            Pop-Location
        }

        $BuiltMod = Get-ChildItem (Join-Path $ModDirectory "build\libs\*.jar") -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notmatch '-(sources|dev|javadoc)\.jar$' } |
                    Sort-Object Length -Descending |
                    Select-Object -First 1

        if (-not $BuiltMod) {
            throw "Gradle reported success but no mod jar was found in bettle-mod\build\libs."
        }

        New-Item -ItemType Directory -Force -Path $BundleDirectory | Out-Null
        Copy-Item $BuiltMod.FullName $BundledModPath -Force
        Write-Ok "Embedded $($BuiltMod.Name) -> bundled\bettle-hud-mod.jar"
    }
}

# --------------------------------------------------------------------------
# 2. Launcher jar
# --------------------------------------------------------------------------

Write-Step "Building the launcher jar"

$MavenArguments = @("-B", "-ntp", "clean", "package")
if ($HasJavaFxJmods) {
    $MavenArguments = @("-B", "-ntp", "-P", "jpackage", "clean", "package")
}
Invoke-Checked $Maven $MavenArguments "Maven build"

$LauncherJar = Get-ChildItem (Join-Path $ProjectRoot "target\BettleClient-*.jar") -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch '-(sources|javadoc|original)' } |
               Sort-Object Length -Descending |
               Select-Object -First 1

if (-not $LauncherJar) { throw "Maven finished but no launcher jar was found in target\." }
Write-Ok "$($LauncherJar.Name)  ($(Format-Size $LauncherJar.Length))"

# --------------------------------------------------------------------------
# 3. jpackage app-image
# --------------------------------------------------------------------------

Write-Step "Building the app image (launcher + private Java runtime)"

$InputDirectory = Join-Path $ProjectRoot "target\jpackage-input"
$AppOutput      = Join-Path $ProjectRoot "build\app"
$AppImage       = Join-Path $AppOutput $AppName

foreach ($d in @($InputDirectory, $AppOutput)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
Copy-Item $LauncherJar.FullName (Join-Path $InputDirectory $LauncherJar.Name) -Force

$Modules  = if ($HasJavaFxJmods) { $BaseModules + $JavaFxModules } else { $BaseModules }
$IconPath = Join-Path $ScriptDirectory "BettleClient.ico"

$JPackageArguments = @(
    "--type",        "app-image"
    "--name",        $AppName
    "--app-version", $AppVersion
    "--vendor",      $Vendor
    "--description", "Crimson Minecraft launcher with dynamic version management"
    "--input",       $InputDirectory
    "--main-jar",    $LauncherJar.Name
    "--main-class",  $MainClass
    "--dest",        $AppOutput
    "--add-modules", ($Modules -join ",")
    "--java-options", "-Xms64m"
    "--java-options", "-Xmx512m"
    "--java-options", "-Dfile.encoding=UTF-8"
    "--java-options", "-Dprism.lcdtext=false"
    "--java-options", "-Dsun.java2d.dpiaware=true"
)
if (Test-Path $IconPath)  { $JPackageArguments += @("--icon", $IconPath) }
if ($HasJavaFxJmods)      { $JPackageArguments += @("--module-path", (Join-Path $JavaHome "jmods")) }

Invoke-Checked $JPackage $JPackageArguments "jpackage"

if (-not (Test-Path $AppImage)) { throw "jpackage did not produce an app image at $AppImage" }
$AppImageSize = (Get-ChildItem $AppImage -Recurse -File | Measure-Object Length -Sum).Sum
Write-Ok "app image: $AppImage  ($(Format-Size $AppImageSize))"

if ($NoInstaller) {
    Write-Step "Done (-NoInstaller)"
    Write-Ok "Run it with: `"$AppImage\$AppName.exe`""
    Write-Host ""
    return
}

# --------------------------------------------------------------------------
# 4. NSIS installer
# --------------------------------------------------------------------------

$DistDirectory = Join-Path $ProjectRoot "dist"
New-Item -ItemType Directory -Force -Path $DistDirectory | Out-Null

$InstallerPath = Join-Path $DistDirectory "BettleClient-Setup-$AppVersion.exe"

if ($Web) {
    Write-Step "Packing the payload zip"
    $PayloadZip = Join-Path $DistDirectory "BettleClient-payload-$AppVersion.zip"
    if (Test-Path $PayloadZip) { Remove-Item $PayloadZip -Force }
    # Contents go at the zip root so Expand-Archive lands them straight in $INSTDIR.
    Compress-Archive -Path (Join-Path $AppImage "*") -DestinationPath $PayloadZip -CompressionLevel Optimal
    Write-Ok "$([System.IO.Path]::GetFileName($PayloadZip))  ($(Format-Size (Get-Item $PayloadZip).Length))"

    Write-Step "Building the downloader stub"
    $NsisArguments = @(
        "/DAPP_VERSION=$AppVersion"
        "/DPAYLOAD_MODE=web"
        "/DPAYLOAD_URL=$PayloadUrl"
        "/DOUT_FILE=$InstallerPath"
        (Join-Path $ScriptDirectory "installer.nsi")
    )
} else {
    Write-Step "Building the self-contained installer"
    $NsisArguments = @(
        "/DAPP_VERSION=$AppVersion"
        "/DPAYLOAD_MODE=embedded"
        "/DPAYLOAD_DIR=$AppImage"
        "/DOUT_FILE=$InstallerPath"
        (Join-Path $ScriptDirectory "installer.nsi")
    )
}

Invoke-Checked $MakeNsis $NsisArguments "makensis"

if (-not (Test-Path $InstallerPath)) {
    throw "makensis reported success but $InstallerPath does not exist."
}

# --------------------------------------------------------------------------
# 5. Report
# --------------------------------------------------------------------------

Write-Step "Done"
Write-Ok "Installer: $InstallerPath"
Write-Ok "Size:      $(Format-Size (Get-Item $InstallerPath).Length)"
Write-Host ""

if ($Web) {
    Write-Info "Two files to publish:"
    Write-Info "  * BettleClient-Setup-$AppVersion.exe        <- what people download"
    Write-Info "  * BettleClient-payload-$AppVersion.zip      <- upload to $PayloadUrl"
    Write-Info "The stub is useless until the zip is live at that exact URL."
} else {
    Write-Info "This is the single file to publish. Users download it, double-click, and get"
    Write-Info "the launcher plus its own Java runtime. No JDK required, and no UAC prompt"
    Write-Info "because it installs per-user into %LOCALAPPDATA%\Programs."
}
Write-Host ""
