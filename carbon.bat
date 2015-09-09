@echo off

set JAVA_EXE=java

:: Only call sbt if the classpath file is missing.
if not exist carbon_classpath.txt (
	rem Read all lines of the sbt runtime classpath output and parse it against the regex supplied to findstr.
	rem The regex looks for lines not starting with '[' and ending in '.jar' as the classpath usually does
	rem (and log lines don't, since they are prefixed with [LOGLEVEL]).
	for /f "tokens=*" %%i in ('sbt "export runtime:dependencyClasspath" ^| findstr "[^\[].*\.jar$"') do (
		echo |set /p=%%i > carbon_classpath.txt
	)
)

:: Read classpath file in rather cumbersome way to avoid the 1024 character buffer limit.
:: Note: this solutions breaks, once the classpath is longer than 8192 characters!
for /f "delims=" %%x in (carbon_classpath.txt) do set CP=%%x

set CARBON_MAIN=viper.carbon.Carbon
set CARBON_OPTS= %*
set CMD=%JAVA_EXE% -Xss30M -cp "%CP%" %CARBON_MAIN% %CARBON_OPTS%

call %CMD%
