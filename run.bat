@echo off
setlocal
cd /d "%~dp0"

if not exist "target\RepoViewer.jar" (
    echo [RepoViewer] 首次运行，正在构建...
    call mvn -q -DskipTests package
    if errorlevel 1 goto :error
)

java -Dfile.encoding=UTF-8 -jar "target\RepoViewer.jar" %*
goto :eof

:error
echo.
echo 构建失败，请检查 Maven 与 JDK 21+ 是否可用。
pause
