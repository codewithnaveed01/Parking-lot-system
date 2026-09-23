@echo off
cd /d %~dp0
if not exist build mkdir build
dir /s /b src\*.java > build\sources.txt
javac -encoding UTF-8 -cp "lib/*" -d build @build\sources.txt
if errorlevel 1 exit /b 1
java -cp "build;lib/*" com.parking.Main %1
