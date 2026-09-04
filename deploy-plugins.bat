@echo off

REM Deploy plugin jars to the local fastcms runtime directory.
REM
REM Usage:
REM   deploy-plugins.bat              build all plugins, then copy jars
REM   deploy-plugins.bat --no-build   copy existing target jars only (no build)
REM
REM Target directory:
REM   %FASTCMS_HOME%\plugins  if FASTCMS_HOME is set
REM   %USERPROFILE%\fastcms\plugins  otherwise (same as web runtime default)
REM
REM NOTE: stop the web app before deploying, a running JVM may lock the jars.
REM After deploy, restart the web app to take effect.

setlocal

if "%FASTCMS_HOME%"=="" (
    set "PLUGIN_DIR=%USERPROFILE%\fastcms\plugins"
) else (
    set "PLUGIN_DIR=%FASTCMS_HOME%\plugins"
)

echo Target plugin dir: %PLUGIN_DIR%

set "SKIP_BUILD="
if /i "%~1"=="--no-build" set "SKIP_BUILD=1"

if not defined SKIP_BUILD (
    echo [1/2] Building plugins ...
    call mvn -f "%~dp0plugins\pom.xml" clean package -DskipTests
    if errorlevel 1 (
        echo Build FAILED, abort.
        exit /b 1
    )
) else (
    echo [1/2] Skip build ^(--no-build^)
)

echo [2/2] Copying plugin jars ...
if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"

set COPIED=0
for /d %%p in ("%~dp0plugins\*-plugin") do (
    if exist "%%p\target\*.jar" (
        xcopy "%%p\target\*.jar" "%PLUGIN_DIR%\" /y /i >nul
        if errorlevel 1 (
            echo   COPY FAILED: %%~nxp ^(jar locked? stop web app first^)
        ) else (
            echo   copied: %%~nxp
            set /a COPIED+=1
        )
    )
)

echo Done. %COPIED% plugin jar^(s^) copied to %PLUGIN_DIR%
echo Restart the web app to take effect.
endlocal
