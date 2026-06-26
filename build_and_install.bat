@echo off
setlocal EnableDelayedExpansion

echo [1/3] Building mod...
call .\gradlew.bat build > gradle_output.txt 2>&1

:: Check if build was successful by looking for "BUILD SUCCESSFUL" in the output
findstr /C:"BUILD SUCCESSFUL" gradle_output.txt > nul
if %ERRORLEVEL% EQU 0 (
    :: Display the gradle output
    type gradle_output.txt
    echo Build successful!
    
    :: Get the newest jar from build\libs
    set "MOD_PATH="
    set "MOD_NAME="
    for /f "usebackq delims=" %%f in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath 'build\libs' -Filter 'belfegor-*.jar' | Where-Object { $_.Name -notlike '*sources.jar' -and $_.Name -notlike '*public*' -and $_.Name -notlike '*obfuscated.jar' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"`) do (
        set "MOD_PATH=%%f"
        for %%i in ("!MOD_PATH!") do set "MOD_NAME=%%~nxi"
    )
    if not defined MOD_PATH (
        echo Build succeeded but no belfegor jar was found in build\libs.
        del gradle_output.txt
        pause
        exit /b 1
    )
    
    echo [2/3] Built mod: !MOD_NAME!

    :: Set mods directory with proper quoting
    set "MODS_DIR=C:\Users\b0052\Desktop\python projects\Projects\mmc-develop-win32\MultiMC\instances\1.21.4\.minecraft\mods"
    
    echo [2.5/3] Checking mods directory...
    if not exist "!MODS_DIR!" (
        echo Creating mods directory...
        mkdir "!MODS_DIR!"
    )
    
    :: Remove existing version if found
    echo [3/3] Installing new version...
    if exist "!MODS_DIR!\!MOD_NAME!" (
        echo Removing existing version: !MOD_NAME!
        del /f "!MODS_DIR!\!MOD_NAME!"
    )
    
    :: Copy new version
    echo Copying new mod file...
    copy /Y "!MOD_PATH!" "!MODS_DIR!\"
    if !ERRORLEVEL! EQU 0 (
        echo.
        echo Installation successful! 
        echo Installed: !MOD_NAME!
        echo Location: !MODS_DIR!
        for /f "tokens=*" %%h in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '!MOD_PATH!').Hash.ToLowerInvariant()"') do set "MOD_SHA256=%%h"
        echo.
        echo Jar SHA-256 for this build:
        echo   MOD_SHA256=!MOD_SHA256!
        echo.
        
        :: Clean up
        del gradle_output.txt
    ) else (
        echo Failed to copy mod file!
        echo From: !MOD_PATH!
        echo To: !MODS_DIR!
        del gradle_output.txt
        pause
        exit /b 1
    )
) else (
    :: Display the gradle output and error message
    type gradle_output.txt
    echo.
    echo Build failed! Check the output above for errors.
    del gradle_output.txt
    pause
    exit /b 1
)

echo.
echo Process completed successfully!
pause
