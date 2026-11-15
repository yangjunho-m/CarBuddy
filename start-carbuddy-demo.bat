@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "APK=%~dp0apk\CarBuddy-debug.apk"
if not exist "%APK%" (
    echo APK missing. Extract the full CarBuddy ZIP before running this file.
    goto :failed
)

set "ADB="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB for /f "delims=" %%A in ('where.exe adb.exe 2^>nul') do if not defined ADB set "ADB=%%A"
if not defined ADB for /f "delims=" %%A in ('powershell.exe -NoProfile -Command "(Get-Process emulator -ErrorAction SilentlyContinue).Path | ForEach-Object { Join-Path (Split-Path (Split-Path $_)) 'platform-tools\adb.exe' }"') do if exist "%%A" set "ADB=%%A"
if not defined ADB (
    echo ADB missing. Install Android SDK Platform-Tools in Android Studio.
    goto :failed
)

set "SERIAL="
for /f "tokens=1,2" %%A in ('""%ADB%" devices"') do if "%%B"=="device" (
    for /f "delims=-" %%C in ("%%A") do if "%%C"=="emulator" (
        if defined SERIAL (
            echo More than one Android emulator is online. Leave only Pleos running.
            goto :failed
        )
        set "SERIAL=%%A"
    )
)
if not defined SERIAL (
    echo No emulator is online. Start Pleos and wait for its home screen.
    goto :failed
)

echo Installing CarBuddy on %SERIAL%...
"%ADB%" -s "%SERIAL%" install -r "%APK%"
if errorlevel 1 (
    echo Installation failed. Wait for Pleos to finish starting and try again.
    goto :failed
)

set "CONSOLE_PORT="
for /f "tokens=1-5 delims=: " %%A in ('""%ADB%" forward --list"') do (
    if "%%A"=="%SERIAL%" if "%%B"=="tcp" if "%%D:%%E"=="tcp:8088" set "CONSOLE_PORT=%%C"
)
if not defined CONSOLE_PORT (
    "%ADB%" -s "%SERIAL%" forward --no-rebind tcp:8088 tcp:8088 >nul 2>&1
    if not errorlevel 1 set "CONSOLE_PORT=8088"
)
if not defined CONSOLE_PORT for /f "delims=" %%A in ('""%ADB%" -s "%SERIAL%" forward tcp:0 tcp:8088"') do set "CONSOLE_PORT=%%A"
if not defined CONSOLE_PORT (
    echo Could not connect to the Pleos Virtual Console.
    goto :failed
)

"%ADB%" -s "%SERIAL%" shell am start --user current -n app.carbuddy/.MainActivity
if errorlevel 1 echo Open CarBuddy from the Pleos app launcher.
echo Opening Pleos Virtual Console at http://127.0.0.1:%CONSOLE_PORT%/
start "" "http://127.0.0.1:%CONSOLE_PORT%/"
if errorlevel 1 (
    echo Open http://127.0.0.1:%CONSOLE_PORT%/ in your browser.
    goto :failed
)
exit /b 0

:failed
pause
exit /b 1
