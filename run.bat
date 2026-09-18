@echo off
cd /d %~dp0
if not exist build mkdir build
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "lib/*" -d build @sources.txt
del sources.txt
java -cp "build;lib/*" com.parking.Main %1 %2
