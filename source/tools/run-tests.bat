@echo off
rem Runs the unit tests without Gradle's test worker.
rem The Gradle test worker cannot start on machines whose user profile path
rem contains non-ASCII characters, so the tests are launched directly via JUnit.
rem
rem Prerequisites: build the debug unit test classes first, e.g.
rem   gradlew.bat :app:compileDebugUnitTestKotlin
rem
setlocal
set ROOT=%~dp0..
set APP=%ROOT%\app

set JAVA_HOME=%JAVA_HOME%
if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

set LIBS=%ROOT%\tools\libs
set CP=%APP%\build\tmp\kotlin-classes\debug;%APP%\build\tmp\kotlin-classes\debugUnitTest;%LIBS%\junit-4.13.2.jar;%LIBS%\hamcrest-core-1.3.jar;%LIBS%\json-20240303.jar;%LIBS%\kotlin-stdlib-2.0.21.jar;%LIBS%\annotations-23.0.0.jar

cd /d "%APP%"
"%JAVA_HOME%\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "%CP%" org.junit.runner.JUnitCore com.aegis.vpn.ConfigGenerationTest
