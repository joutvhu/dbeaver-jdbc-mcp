@echo off
setlocal enabledelayedexpansion

REM Resolve directory of this script
set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%dbeaver-jdbc-mcp.jar"
if not exist "%JAR_PATH%" set "JAR_PATH=%SCRIPT_DIR%target\dbeaver-jdbc-mcp.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] Could not find dbeaver-jdbc-mcp.jar in %SCRIPT_DIR% >&2
    echo Please make sure dbeaver-jdbc-mcp.jar is placed in the same directory as this script, or run "mvn package" first. >&2
    exit /b 1
)

set "JAVA_CMD="

REM 1. Check DBeaver installation from Windows Registry (HKLM, WOW6432Node, HKCU)
for %%K in (
    "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"
    "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
    "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"
) do (
    for /f "tokens=*" %%A in ('reg query %%K 2^>nul ^| findstr /i "DBeaver"') do (
        for /f "tokens=2,*" %%B in ('reg query "%%A" /v "InstallLocation" 2^>nul ^| findstr /i "InstallLocation"') do (
            if exist "%%C\jre\bin\java.exe" (
                set "JAVA_CMD=%%C\jre\bin\java.exe"
                goto :found_java
            )
        )
    )
)

REM 2. Check standard Program Files environment variables
if defined ProgramFiles (
    if exist "%ProgramFiles%\DBeaver\jre\bin\java.exe" (
        set "JAVA_CMD=%ProgramFiles%\DBeaver\jre\bin\java.exe"
        goto :found_java
    )
)
if defined ProgramFiles(x86) (
    if exist "%ProgramFiles(x86)%\DBeaver\jre\bin\java.exe" (
        set "JAVA_CMD=%ProgramFiles(x86)%\DBeaver\jre\bin\java.exe"
        goto :found_java
    )
)

REM 3. Check dynamically detected existing drives via fsutil
for /f "tokens=1* delims=: " %%A in ('fsutil fsinfo drives 2^>nul') do (
    for %%C in (%%B) do (
        if exist "%%CProgram Files\DBeaver\jre\bin\java.exe" (
            set "JAVA_CMD=%%CProgram Files\DBeaver\jre\bin\java.exe"
            goto :found_java
        )
        if exist "%%CProgram Files (x86)\DBeaver\jre\bin\java.exe" (
            set "JAVA_CMD=%%CProgram Files (x86)\DBeaver\jre\bin\java.exe"
            goto :found_java
        )
    )
)

REM Fallback: scan existing drive roots from C to Z
for %%D in (C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    if exist "%%D:\" (
        if exist "%%D:\Program Files\DBeaver\jre\bin\java.exe" (
            set "JAVA_CMD=%%D:\Program Files\DBeaver\jre\bin\java.exe"
            goto :found_java
        )
        if exist "%%D:\Program Files (x86)\DBeaver\jre\bin\java.exe" (
            set "JAVA_CMD=%%D:\Program Files (x86)\DBeaver\jre\bin\java.exe"
            goto :found_java
        )
    )
)

REM 4. Check Local AppData installation (User-level installer)
if defined LOCALAPPDATA (
    if exist "%LOCALAPPDATA%\Programs\DBeaver\jre\bin\java.exe" (
        set "JAVA_CMD=%LOCALAPPDATA%\Programs\DBeaver\jre\bin\java.exe"
        goto :found_java
    )
)

REM 5. Check JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        goto :found_java
    )
)

REM 6. Fallback to system PATH java
set "JAVA_CMD=java"

:found_java
"%JAVA_CMD%" -jar "%JAR_PATH%" %*
