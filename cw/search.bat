@echo off
rem
rem
setlocal

if defined SEARCH_JAR goto run

rem
for /f "delims=" %%f in ('dir /b /o-s "%~dp0target\*.jar" 2^>nul') do (
    set "SEARCH_JAR=%~dp0target\%%f"
    goto run
)

echo Jar not found. Build the project first: mvn package 1>&2
exit /b 1

:run
java -cp "%SEARCH_JAR%" client.SearchCli %*
exit /b %ERRORLEVEL%
