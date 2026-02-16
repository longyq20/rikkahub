@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  backend-server startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and BACKEND_SERVER_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\backend-server.jar;%APP_HOME%\lib\backend-migration.jar;%APP_HOME%\lib\backend-storage-sqlite.jar;%APP_HOME%\lib\backend-core.jar;%APP_HOME%\lib\ktor-server-auth-jwt-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-auth-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-serialization-kotlinx-json-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-client-okhttp-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-client-content-negotiation-jvm-3.4.0.jar;%APP_HOME%\lib\kotlin-sdk-jvm-0.8.3.jar;%APP_HOME%\lib\kotlin-sdk-client-jvm-0.8.3.jar;%APP_HOME%\lib\ktor-client-core-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-default-headers-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-conditional-headers-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-compression-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-cors-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-content-negotiation-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-status-pages-jvm-3.4.0.jar;%APP_HOME%\lib\kotlin-sdk-server-jvm-0.8.3.jar;%APP_HOME%\lib\ktor-server-sse-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-cio-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-server-sessions-jvm-3.4.0.jar;%APP_HOME%\lib\kotlin-sdk-core-jvm-0.8.3.jar;%APP_HOME%\lib\ktor-server-websockets-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-server-core-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-http-cio-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-websocket-serialization-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-serialization-kotlinx-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-serialization-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-websockets-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-http-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-events-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-sse-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-network-jvm-3.4.0.jar;%APP_HOME%\lib\ktor-utils-jvm-3.4.0.jar;%APP_HOME%\lib\kotlinx-serialization-json-io-jvm-1.10.0.jar;%APP_HOME%\lib\kotlinx-serialization-core-jvm-1.10.0.jar;%APP_HOME%\lib\kotlinx-serialization-json-jvm-1.10.0.jar;%APP_HOME%\lib\kotlinx-coroutines-slf4j-1.10.2.jar;%APP_HOME%\lib\ktor-io-jvm-3.4.0.jar;%APP_HOME%\lib\kotlinx-coroutines-core-jvm-1.10.2.jar;%APP_HOME%\lib\kotlin-reflect-2.3.0.jar;%APP_HOME%\lib\okhttp-jvm-5.3.2.jar;%APP_HOME%\lib\okio-jvm-3.16.4.jar;%APP_HOME%\lib\kotlinx-io-core-jvm-0.8.2.jar;%APP_HOME%\lib\kotlinx-collections-immutable-jvm-0.4.0.jar;%APP_HOME%\lib\kotlin-logging-jvm-7.0.14.jar;%APP_HOME%\lib\kotlinx-io-bytestring-jvm-0.8.2.jar;%APP_HOME%\lib\kotlin-stdlib-2.3.0.jar;%APP_HOME%\lib\java-jwt-4.5.0.jar;%APP_HOME%\lib\jsoup-1.22.1.jar;%APP_HOME%\lib\slf4j-simple-2.0.17.jar;%APP_HOME%\lib\annotations-23.0.0.jar;%APP_HOME%\lib\sqlite-jdbc-3.50.3.0.jar;%APP_HOME%\lib\jwks-rsa-0.23.0.jar;%APP_HOME%\lib\jackson-annotations-2.15.4.jar;%APP_HOME%\lib\jackson-databind-2.15.4.jar;%APP_HOME%\lib\jackson-core-2.15.4.jar;%APP_HOME%\lib\slf4j-api-2.0.17.jar;%APP_HOME%\lib\config-1.4.5.jar;%APP_HOME%\lib\jansi-2.4.2.jar;%APP_HOME%\lib\guava-32.1.2-jre.jar;%APP_HOME%\lib\failureaccess-1.0.1.jar;%APP_HOME%\lib\listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar;%APP_HOME%\lib\jsr305-3.0.2.jar;%APP_HOME%\lib\checker-qual-3.33.0.jar;%APP_HOME%\lib\error_prone_annotations-2.18.0.jar


@rem Execute backend-server
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %BACKEND_SERVER_OPTS%  -classpath "%CLASSPATH%" me.rerere.rikkahub.backend.server.MainKt %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable BACKEND_SERVER_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%BACKEND_SERVER_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
