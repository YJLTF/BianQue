@echo off
REM ============================================
REM 扁鹊 BianQue 全栈一键启动（Windows）
REM 用法: scripts\start-all.bat
REM 可选环境变量:
REM   JAVA_HOME       默认 F:\ide\java\jdk-21.0.11
REM   INGEST_TOKEN    告警接入 token（缺省 bianque-ingest-token）
REM ============================================
setlocal
if "%JAVA_HOME%"=="" set JAVA_HOME=F:\ide\java\jdk-21.0.11
if "%INGEST_TOKEN%"=="" set INGEST_TOKEN=bianque-ingest-token
cd /d %~dp0..

echo [1/4] 基础设施：MySQL + Redis（Docker）...
docker compose -f deploy/docker-compose.yml up -d mysql redis || goto :err

echo [2/4] 构建后端...
cd server
call mvn -B package -DskipTests -q || goto :err
cd ..

echo [3/4] 启动后端（MySQL+Redis 模式，端口 8080）...
start "bq-server" /min "%JAVA_HOME%\bin\java" -jar server\bq-server\target\bq-server-0.1.0-SNAPSHOT.jar ^
  --spring.profiles.active=mysql --bianque.alerts.ingest-token=%INGEST_TOKEN%

echo [4/4] 启动前端开发服务（端口 5173）...
cd web
start "bq-web" /min cmd /c "npm run dev"
cd ..

echo.
echo 完成后访问 http://localhost:5173 （账号 admin/admin123）
echo 靶场演示进程（可选）: "%JAVA_HOME%\bin\java" -jar docs\research\arthas-inspect\math-game.jar
exit /b 0

:err
echo 启动失败，请检查上方日志
exit /b 1
