@echo off
cd /d d:\git\system-design

echo Building...
call mvnw.cmd clean install -q
if errorlevel 1 exit /b 1
echo Build successful!

echo.
echo Pushing to GitHub...
if not exist .git (
    git init
    git add .
    git commit -m "Initial commit: System design implementations"
    git branch -M main
    git remote add origin git@github.com:RohanYadav1812/system-design.git
)
git add .
git commit -m "Update" 2>nul
git push -u origin main

echo Done!
