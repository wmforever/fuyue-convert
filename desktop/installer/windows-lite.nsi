Unicode true
RequestExecutionLevel user
SetCompressor zlib
CRCCheck force
SetOverwrite on

Name "${PRODUCT_NAME}"
Caption "${PRODUCT_NAME} ${APP_VERSION} Setup"
OutFile "${OUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
BrandingText "${PRODUCT_NAME}"

VIProductVersion "${APP_VERSION4}"
VIAddVersionKey /LANG=1033 "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey /LANG=1033 "ProductVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "FileDescription" "${PRODUCT_NAME} Windows x64 Installer"
VIAddVersionKey /LANG=1033 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Fuyue Convert contributors"

LicenseText "Please review the Fuyue Convert source license before installing."
LicenseData "${PROJECT_LICENSE}"
Page license
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

Function .onInit
  ; The public installer owns one fixed per-user directory. Never honour /D,
  ; a stale registry value, or an interactive path for a recursive uninstall.
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
FunctionEnd

Section "Install"
  SetShellVarContext current
  StrCpy $INSTDIR "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
  SetOutPath "$INSTDIR"
  File /r "${APP_SOURCE}\*"

  WriteUninstaller "$INSTDIR\Uninstall ${PRODUCT_NAME}.exe"
  WriteRegStr HKCU "Software\${PRODUCT_NAME}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "DisplayIcon" "$INSTDIR\${PRODUCT_EXE}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "UninstallString" '"$INSTDIR\Uninstall ${PRODUCT_NAME}.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}" "NoRepair" 1

  CreateDirectory "$SMPROGRAMS\${PRODUCT_NAME}"
  CreateShortCut "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk" "$INSTDIR\${PRODUCT_EXE}"
  CreateShortCut "$DESKTOP\${PRODUCT_NAME}.lnk" "$INSTDIR\${PRODUCT_EXE}"
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  StrCmp $INSTDIR "$LOCALAPPDATA\Programs\${PRODUCT_NAME}" uninstall_path_valid 0
  Abort "Refusing to uninstall from an unexpected directory."
uninstall_path_valid:
  IfFileExists "$INSTDIR\resources\app.asar" uninstall_marker_valid 0
  Abort "Fuyue Convert application marker is missing."
uninstall_marker_valid:
  ; NSIS runs an uninstaller copy from TEMP while preserving the original
  ; install directory in $INSTDIR. Move out of that directory and delete the
  ; original uninstaller before recursively removing the owned tree.
  SetOutPath "$TEMP"
  Delete "$DESKTOP\${PRODUCT_NAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk"
  RMDir "$SMPROGRAMS\${PRODUCT_NAME}"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PRODUCT_NAME}"
  DeleteRegKey HKCU "Software\${PRODUCT_NAME}"
  Delete "$INSTDIR\Uninstall ${PRODUCT_NAME}.exe"
  ClearErrors
  RMDir /r "$INSTDIR"
  IfErrors 0 uninstall_complete
  SetErrorLevel 1
  Abort "Unable to remove the Fuyue Convert installation directory."
uninstall_complete:
SectionEnd
