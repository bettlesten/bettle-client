@echo off
REM ===========================================================================
REM  Bettle Client - Windows installer build (double-clickable wrapper)
REM
REM  Produces dist\BettleClient-Setup-1.0.0.exe : a single branded installer
REM  carrying the launcher and its own Java runtime.
REM
REM  Requirements:
REM    * JDK 17+  (Liberica Full JDK recommended - it bundles JavaFX)
REM    * Maven 3.8+
REM    * NSIS 3.x   ->  choco install nsis -y
REM    * Gradle     ->  optional, builds the HUD mod to embed
REM
REM  See build-exe.ps1 for options: -Web, -NoInstaller, -SkipMod, -AppVersion.
REM ===========================================================================

setlocal
set "SCRIPT_DIR=%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%build-exe.ps1" %*
set "RESULT=%ERRORLEVEL%"

if not "%RESULT%"=="0" (
    echo.
    echo Build failed with exit code %RESULT%.
)

echo.
pause
exit /b %RESULT%
