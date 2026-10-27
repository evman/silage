; Per-user Silage installer (Inno Setup 6). Built by package-windows.cmd / package-windows.py:
;   ISCC /DVersion=0.5.0 /DAppDir=<repo>\dist\Silage /O<repo>\dist packaging\windows\silage.iss
; Relative paths below are relative to this file's folder.
;
; Command line used by the in-app updater:
;   Silage-<v>-Setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /RELAUNCH
; /RELAUNCH (Silage's own switch) starts Silage after a silent install; without it a
; silent install leaves Silage closed. An interactive install always starts it.

#ifndef Version
  #error Pass the version: ISCC /DVersion=x.y.z
#endif
#ifndef AppDir
  #define AppDir "..\..\dist\Silage"
#endif

[Setup]
; Never change AppId: Windows and Setup recognise upgrades and the uninstall entry by it.
AppId={{EC6708E9-2E25-4B67-A3D7-666423B0CD00}
AppName=Silage
AppVersion={#Version}
AppVerName=Silage {#Version}
AppPublisher=Silage
AppPublisherURL=https://github.com/evman/silage
AppSupportURL=https://github.com/evman/silage/issues
AppUpdatesURL=https://github.com/evman/silage/releases
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\Silage
DisableWelcomePage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
DisableFinishedPage=yes
CloseApplications=force
RestartApplications=no
UninstallDisplayName=Silage
UninstallDisplayIcon={app}\silage.exe
SetupIconFile=silage.ico
OutputDir=..\..\dist
OutputBaseFilename=Silage-{#Version}-Setup
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
Compression=lzma2
SolidCompression=yes

[InstallDelete]
; An upgrade replaces the app image whole, so files a newer build no longer ships don't linger.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Same AppUserModelID as the running app (WindowsChrome.APP_USER_MODEL_ID), so the taskbar groups them.
Name: "{userprograms}\Silage"; Filename: "{app}\silage.exe"; AppUserModelID: "dev.silage.Silage"
Name: "{userdesktop}\Silage"; Filename: "{app}\silage.exe"; AppUserModelID: "dev.silage.Silage"

[Run]
Filename: "{app}\silage.exe"; Description: "Start Silage"; Flags: nowait postinstall; Check: ShouldStartSilage

[Code]
var
  DeleteUserData: Boolean;

function RelaunchRequested: Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), '/RELAUNCH') = 0 then
    begin
      Result := True;
      Exit;
    end;
end;

function ShouldStartSilage: Boolean;
begin
  Result := (not WizardSilent) or RelaunchRequested;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  // Asked only in an interactive uninstall; the default button is No.
  if (CurUninstallStep = usUninstall) and not UninstallSilent then
    DeleteUserData := MsgBox('Also delete your Silage settings and sign-in?',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
  if (CurUninstallStep = usPostUninstall) and DeleteUserData then
  begin
    DelTree(ExpandConstant('{userappdata}\Silage'), True, True, True);
    DelTree(ExpandConstant('{localappdata}\Silage'), True, True, True);
  end;
end;
