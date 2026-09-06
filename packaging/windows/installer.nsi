; ============================================================================
;  Bettle Client - NSIS installer
; ----------------------------------------------------------------------------
;  Produces a single branded .exe: the kind of installer people expect from a
;  Minecraft client, not a Windows Installer .msi wrapper.
;
;  It packages the app-image that jpackage produces (the launcher plus its own
;  trimmed Java runtime), so the end user installs one file and needs no JDK.
;
;  Build:
;    makensis /DAPP_VERSION=1.0.0 /DPAYLOAD_DIR=..\..\build\app\BettleClient installer.nsi
;
;  Payload modes:
;    /DPAYLOAD_MODE=embedded   (default) everything inside the exe, works offline
;    /DPAYLOAD_MODE=web        small stub that downloads the payload zip from
;                              /DPAYLOAD_URL=https://.../BettleClient-payload.zip
;
;  Requires only vanilla NSIS 3.x - no third-party plugins. The web mode uses
;  Windows PowerShell (present on every Windows 8.1+ machine) for the download
;  and the unzip, so there is nothing extra to install on the build machine.
; ============================================================================

Unicode true
ManifestDPIAware true

; ----------------------------------------------------------------------------
;  Overridable defines
; ----------------------------------------------------------------------------

!ifndef APP_VERSION
  !define APP_VERSION "1.0.0"
!endif
!ifndef PAYLOAD_MODE
  !define PAYLOAD_MODE "embedded"
!endif
!ifndef PAYLOAD_DIR
  !define PAYLOAD_DIR "..\..\build\app\Bettle Client"
!endif
!ifndef PAYLOAD_URL
  !define PAYLOAD_URL ""
!endif
!ifndef OUT_FILE
  !define OUT_FILE "..\..\dist\BettleClient-Setup-${APP_VERSION}.exe"
!endif

!define APP_NAME        "Bettle Client"
!define APP_SLUG        "BettleClient"
!define APP_PUBLISHER   "Bettle Client"
!define APP_EXE         "Bettle Client.exe"
!define APP_URL         "https://github.com/bettleclient"
!define UNINST_KEY      "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SLUG}"
!define SETTINGS_KEY    "Software\${APP_SLUG}"

; ----------------------------------------------------------------------------
;  Installer attributes
; ----------------------------------------------------------------------------

Name "${APP_NAME}"
OutFile "${OUT_FILE}"
BrandingText "${APP_NAME} ${APP_VERSION}"

; Per-user install: lands in %LOCALAPPDATA%\Programs, so Windows never shows a
; UAC prompt. That is the single biggest thing that makes an installer feel
; light to a end user.
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Programs\${APP_SLUG}"
InstallDirRegKey HKCU "${SETTINGS_KEY}" "InstallDir"

SetCompressor /SOLID lzma
SetCompressorDictSize 64
SetDatablockOptimize on
CRCCheck on
XPStyle on

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey /LANG=1033 "ProductName"      "${APP_NAME}"
VIAddVersionKey /LANG=1033 "FileDescription"  "${APP_NAME} Setup"
VIAddVersionKey /LANG=1033 "FileVersion"      "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "ProductVersion"   "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "CompanyName"      "${APP_PUBLISHER}"
VIAddVersionKey /LANG=1033 "LegalCopyright"   "${APP_PUBLISHER}"
VIAddVersionKey /LANG=1033 "OriginalFilename" "BettleClient-Setup-${APP_VERSION}.exe"

; ----------------------------------------------------------------------------
;  Includes
; ----------------------------------------------------------------------------

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"
!include "WinVer.nsh"
!include "WordFunc.nsh"

; ----------------------------------------------------------------------------
;  Modern UI - crimson branding
; ----------------------------------------------------------------------------

!define MUI_ICON   "BettleClient.ico"
!define MUI_UNICON "BettleClient.ico"

!define MUI_WELCOMEFINISHPAGE_BITMAP    "welcome.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP  "welcome.bmp"
!define MUI_WELCOMEFINISHPAGE_BITMAP_NOSTRETCH
!define MUI_UNWELCOMEFINISHPAGE_BITMAP_NOSTRETCH

!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP          "header.bmp"
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_UNBITMAP        "header.bmp"

!define MUI_ABORTWARNING
!define MUI_ABORTWARNING_TEXT "Cancel the ${APP_NAME} installation?"
!define MUI_UNABORTWARNING

!define MUI_WELCOMEPAGE_TITLE "Install ${APP_NAME}"
!define MUI_WELCOMEPAGE_TEXT  "${APP_NAME} is a Minecraft launcher with dynamic version support from 1.20.1 onward, official Microsoft account sign-in, and an optional in-game HUD.$\r$\n$\r$\nEverything it needs, including its own Java runtime, is installed for you. Nothing else has to be downloaded first.$\r$\n$\r$\nClick Next to continue."

!define MUI_DIRECTORYPAGE_TEXT_TOP "${APP_NAME} will be installed in the folder below. It installs for your account only, so Windows will not ask for administrator permission.$\r$\n$\r$\nTo choose a different folder, click Browse."

!define MUI_FINISHPAGE_TITLE "${APP_NAME} is ready"
!define MUI_FINISHPAGE_TEXT  "${APP_NAME} has been installed on your computer.$\r$\n$\r$\nSign in with your Microsoft account, pick a Minecraft version, and press PLAY."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Launch ${APP_NAME} now"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApplication
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a desktop shortcut"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateDesktopShortcut

; ---- pages ----
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

; ----------------------------------------------------------------------------
;  Helpers
; ----------------------------------------------------------------------------

Var PreviousUninstaller
Var PreviousVersion

Function LaunchApplication
  ; ExecShell rather than Exec: the app starts detached and with the user's
  ; own token, so closing the installer does not take the launcher with it.
  ExecShell "" "$INSTDIR\${APP_EXE}"
FunctionEnd

Function CreateDesktopShortcut
  CreateShortCut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
FunctionEnd

; Refuses to install over a running copy, which is what produces the classic
; "some files could not be written" half-installed state.
Function CheckNotRunning
  retry:
    FindWindow $0 "" "${APP_NAME}"
    ${If} $0 != 0
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION \
        "${APP_NAME} is currently running.$\r$\n$\r$\nPlease close it and click Retry." \
        /SD IDCANCEL IDRETRY retry
      Abort
    ${EndIf}
FunctionEnd

; ----------------------------------------------------------------------------
;  Init
; ----------------------------------------------------------------------------

Function .onInit
  ${IfNot} ${AtLeastWin7}
    MessageBox MB_OK|MB_ICONSTOP \
      "${APP_NAME} requires Windows 7 SP1 or newer." /SD IDOK
    Abort
  ${EndIf}

  Call CheckNotRunning

  ; ---- upgrade in place ----
  ReadRegStr $PreviousUninstaller HKCU "${UNINST_KEY}" "UninstallString"
  ReadRegStr $PreviousVersion     HKCU "${UNINST_KEY}" "DisplayVersion"

  ${If} $PreviousUninstaller != ""
    ${VersionCompare} "${APP_VERSION}" "$PreviousVersion" $0
    ${If} $0 == 2
      MessageBox MB_YESNO|MB_ICONQUESTION \
        "A newer build ($PreviousVersion) is already installed.$\r$\n$\r$\nInstall the older ${APP_VERSION} over it anyway?" \
        /SD IDYES IDYES continueInstall
      Abort
    ${EndIf}

    continueInstall:
    DetailPrint "Removing the previous installation ($PreviousVersion)..."
    ; /S silent, _?= keeps the uninstaller synchronous so the copy below does
    ; not race the removal.
    ExecWait '"$PreviousUninstaller" /S _?=$INSTDIR' $0
    Delete "$INSTDIR\Uninstall.exe"
  ${EndIf}
FunctionEnd

; ----------------------------------------------------------------------------
;  Install
; ----------------------------------------------------------------------------

Section "${APP_NAME}" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  SetOverwrite on

!if "${PAYLOAD_MODE}" == "web"

  ; ------------------------------------------------------------------
  ;  Web stub: fetch the payload at install time.
  ;  Keeps the download around 1-2 MB at the cost of needing a network
  ;  connection and somewhere to host the zip (a GitHub Release asset works).
  ; ------------------------------------------------------------------
  !if "${PAYLOAD_URL}" == ""
    !error "PAYLOAD_MODE=web requires /DPAYLOAD_URL=<https url to the payload zip>"
  !endif

  DetailPrint "Downloading ${APP_NAME} ${APP_VERSION}..."
  StrCpy $2 "$PLUGINSDIR\payload.zip"

  ; PowerShell rather than an NSIS download plugin: it speaks modern TLS, it is
  ; present on every supported Windows, and it keeps this script buildable with
  ; a stock NSIS install.
  nsExec::ExecToLog 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$ErrorActionPreference=$\'Stop$\'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri $\'${PAYLOAD_URL}$\' -OutFile $\'$2$\' -UseBasicParsing"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP \
      "Could not download ${APP_NAME}.$\r$\n$\r$\nCheck your internet connection and try again." /SD IDOK
    Abort
  ${EndIf}

  DetailPrint "Extracting..."
  nsExec::ExecToLog 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$ErrorActionPreference=$\'Stop$\'; Expand-Archive -LiteralPath $\'$2$\' -DestinationPath $\'$INSTDIR$\' -Force"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP "Could not extract the ${APP_NAME} payload." /SD IDOK
    Abort
  ${EndIf}
  Delete "$2"

!else

  ; ------------------------------------------------------------------
  ;  Embedded: the whole app-image ships inside this exe.
  ;  One file, works with no network at all.
  ; ------------------------------------------------------------------
  DetailPrint "Installing ${APP_NAME} and its Java runtime..."
  File /r "${PAYLOAD_DIR}\*.*"

!endif

  ; ---- uninstaller ----
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; ---- settings ----
  WriteRegStr HKCU "${SETTINGS_KEY}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${SETTINGS_KEY}" "Version"    "${APP_VERSION}"

  ; ---- Add/Remove Programs ----
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0

  WriteRegStr   HKCU "${UNINST_KEY}" "DisplayName"     "${APP_NAME}"
  WriteRegStr   HKCU "${UNINST_KEY}" "DisplayVersion"  "${APP_VERSION}"
  WriteRegStr   HKCU "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\${APP_EXE},0"
  WriteRegStr   HKCU "${UNINST_KEY}" "Publisher"       "${APP_PUBLISHER}"
  WriteRegStr   HKCU "${UNINST_KEY}" "URLInfoAbout"    "${APP_URL}"
  WriteRegStr   HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr   HKCU "${UNINST_KEY}" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegStr   HKCU "${UNINST_KEY}" "QuietUninstallString" "$\"$INSTDIR\Uninstall.exe$\" /S"
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize"   "$0"
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify"        1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair"        1

  ; ---- Start Menu ----
  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut  "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" \
                  "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
  CreateShortCut  "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk" \
                  "$INSTDIR\Uninstall.exe"
SectionEnd

; ----------------------------------------------------------------------------
;  Uninstall
; ----------------------------------------------------------------------------

Function un.onInit
  retry:
    FindWindow $0 "" "${APP_NAME}"
    ${If} $0 != 0
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION \
        "${APP_NAME} is currently running.$\r$\n$\r$\nPlease close it and click Retry." \
        /SD IDCANCEL IDRETRY retry
      Abort
    ${EndIf}
FunctionEnd

Section "Uninstall"
  ; Program files and the bundled runtime.
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\Uninstall.exe"
  Delete "$INSTDIR\*.dll"
  Delete "$INSTDIR\*.cfg"
  Delete "$INSTDIR\*.ico"
  RMDir "$INSTDIR"

  ; Shortcuts.
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk"
  RMDir  "$SMPROGRAMS\${APP_NAME}"
  Delete "$DESKTOP\${APP_NAME}.lnk"

  ; Registry.
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "${SETTINGS_KEY}"

  ; Game data, saves, versions and the signed-in session live in
  ; %APPDATA%\.bettleclient and are deliberately left alone. Removing a
  ; player's worlds because they uninstalled a launcher would be indefensible;
  ; offer it instead.
  IfFileExists "$APPDATA\.bettleclient\*.*" 0 done
    MessageBox MB_YESNO|MB_ICONQUESTION \
      "Also delete your Minecraft data for ${APP_NAME}?$\r$\n$\r$\nThis removes downloaded versions, instances, worlds and your saved sign-in from:$\r$\n$APPDATA\.bettleclient$\r$\n$\r$\nChoose No to keep them." \
      /SD IDNO IDNO done
    RMDir /r "$APPDATA\.bettleclient"
  done:
SectionEnd
