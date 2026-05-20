@echo off
REM =====================================================================
REM ChatPilot one-click build and install
REM
REM What this does:
REM   1. Cleans any previous build cache
REM   2. Builds chatpilot-1.2.1.jar
REM   3. Tells you exactly where the new jar lives
REM   4. Optionally copies it into your Modrinth mods folder
REM
REM Just double-click this file from inside the chatpilot folder.
REM =====================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ========================================
echo  ChatPilot 1.2.1 build script
echo ========================================
echo.

REM --- Step 1: clean ---
echo [1/3] Cleaning previous build...
if exist "build" (
    rmdir /s /q "build"
    echo     build folder removed
) else (
    echo     no previous build to clean
)
if exist ".gradle" (
    rmdir /s /q ".gradle"
    echo     .gradle cache removed
)
echo.

REM --- Step 2: build ---
echo [2/3] Building... this will take 1 to 5 minutes the first time.
echo.

REM Try gradle wrapper first, then system gradle
if exist "gradlew.bat" (
    call gradlew.bat build
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo.
        echo ========================================
        echo  ERROR: Gradle is not installed
        echo ========================================
        echo.
        echo Download Gradle from: https://gradle.org/install/
        echo Or open this folder in IntelliJ IDEA which bundles gradle automatically.
        echo.
        pause
        exit /b 1
    )
    call gradle build
)

if errorlevel 1 (
    echo.
    echo ========================================
    echo  BUILD FAILED. See errors above.
    echo ========================================
    echo.
    pause
    exit /b 1
)

echo.

REM --- Step 3: locate jar and prompt to install ---
echo [3/3] Build complete.
echo.

set "JAR=build\libs\chatpilot-1.2.1.jar"

if not exist "%JAR%" (
    echo ERROR: expected jar not found at %JAR%
    echo Looking for whatever jar got produced...
    dir /b build\libs\*.jar
    pause
    exit /b 1
)

echo Built: %CD%\%JAR%
echo.

REM Try to find Modrinth mods folder
set "MODRINTH_BASE=%APPDATA%\ModrinthApp\profiles"

if not exist "%MODRINTH_BASE%" (
    echo Modrinth profiles folder not found at the default location.
    echo Copy %JAR% to your mods folder manually, then delete any old chatpilot-1.0.0.jar.
    echo.
    pause
    exit /b 0
)

echo Modrinth profiles found at: %MODRINTH_BASE%
echo.
echo Available profiles:
dir /b "%MODRINTH_BASE%"
echo.
set /p "PROFILE=Type the exact profile name to install into (or press Enter to skip): "

if "%PROFILE%"=="" (
    echo Skipped. Manually copy %JAR% to your mods folder.
    pause
    exit /b 0
)

set "MODS=%MODRINTH_BASE%\%PROFILE%\mods"

if not exist "%MODS%" (
    echo ERROR: mods folder not found at %MODS%
    pause
    exit /b 1
)

echo.
echo Cleaning out any old chatpilot jars from %MODS%...
del /q "%MODS%\chatpilot-*.jar" 2>nul
del /q "%MODS%\chatpilot.jar" 2>nul

echo Copying new jar...
copy /y "%JAR%" "%MODS%\chatpilot-1.2.1.jar"

if errorlevel 1 (
    echo ERROR: copy failed
    pause
    exit /b 1
)

echo.
echo ========================================
echo  DONE. Launch Minecraft now.
echo ========================================
echo Look for "chatpilot 1.2.1" in the mods list. If you still see 1.0.0,
echo you have a leftover jar somewhere else (check resourcepacks, other
echo profiles, or the wrong instance).
echo.
pause
