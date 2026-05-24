@echo off
setlocal

set TEST_NS=cn.li.ac.block.wireless-node-test,cn.li.ac.block.wireless-node-handlers-test,cn.li.ac.block.wireless-node-gui-policy-test,cn.li.ac.block.wireless-node-gui-sync-test,cn.li.ac.block.wireless-node-quick-move-test

echo [node-focused] Stopping Gradle daemons to release stale locks...
call .\gradlew.bat --stop
if errorlevel 1 exit /b %errorlevel%

echo [node-focused] Cleaning ac\build\clojure\test lock-prone output directory...
if exist ac\build\clojure\test rmdir /s /q ac\build\clojure\test

echo [node-focused] Running focused AC tests via fast source-classpath runner...
call .\gradlew.bat --no-daemon --console=plain :ac:runAcClojureTestsFast "-Dac.test.only=%TEST_NS%"
exit /b %errorlevel%
