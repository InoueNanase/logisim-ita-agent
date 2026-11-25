@echo off
setlocal
where python >nul 2>nul
if not errorlevel 1 (
    python "%~dp0client-python\logisim_agent.py" %*
) else (
    py -3 "%~dp0client-python\logisim_agent.py" %*
)
exit /b %errorlevel%
