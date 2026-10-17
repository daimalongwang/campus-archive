@echo off
rem ================================================================
rem  Campus Archive - launcher
rem
rem  !!! KEEP THIS FILE ASCII-ONLY !!!
rem  A .bat containing UTF-8 Chinese comments is mis-parsed by
rem  cmd.exe (it decodes the file with the console code page), which
rem  produces errors such as:
rem      'ed' is not recognized as an internal or external command
rem  Chinese output of the app itself is handled by "chcp 65001".
rem ================================================================
setlocal
cd /d "%~dp0"

rem ---------- 1. locate JDK ----------
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if defined JDK goto :havejdk
for /d %%D in ("C:\Software\jdk-*") do if not defined JDK if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if defined JDK goto :havejdk
for /f "delims=" %%J in ('where javac 2^>nul') do if not defined JDK set "JDK=%%~dpJ.."
if defined JDK goto :havejdk
echo [ERROR] JDK not found. Install JDK 17+ and set JAVA_HOME, e.g.
echo         set JAVA_HOME=C:\Software\jdk-21.0.12.1
pause
exit /b 1
:havejdk
set "JAVA_HOME=%JDK%"
echo [env] JDK   = %JAVA_HOME%

rem ---------- 2. locate Maven ----------
set "MVN="
if exist "C:\Software\apache-maven-3.9.16\bin\mvn.cmd" set "MVN=C:\Software\apache-maven-3.9.16\bin\mvn.cmd"
if defined MVN goto :havemvn
for /f "delims=" %%M in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%~fM"
if defined MVN goto :havemvn
echo [ERROR] Maven not found. Install Maven and add its bin to PATH.
pause
exit /b 1
:havemvn
echo [env] Maven = %MVN%

rem ---------- 3. compile and resolve runtime classpath ----------
if exist "target\classes" (echo [1/2] compiling ...) else (echo [1/2] first run: compiling and downloading dependencies, needs network ...)
call "%MVN%" -q -B compile dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
if errorlevel 1 (
  echo.
  echo [ERROR] build failed - see the messages above.
  pause
  exit /b 1
)

set "CP="
set /p CP=<target\cp.txt
if not defined CP (
  echo [ERROR] cannot read target\cp.txt - run this script again.
  pause
  exit /b 1
)

rem ---------- 4. launch ----------
set "HOMEOPT="
if defined ARCHIVE_HOME set "HOMEOPT=-Darchive.home=%ARCHIVE_HOME%"
echo [2/2] starting ...
echo.
echo   data dir : %USERPROFILE%\.archive-app   (override with ARCHIVE_HOME)
echo   close the window to quit.
echo.
chcp 65001 >nul
java -Dfile.encoding=UTF-8 %HOMEOPT% -cp "target\classes;%CP%" com.campus.archive.Launcher %*
endlocal
