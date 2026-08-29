@echo off
cd /d "%~dp0"
set "PYTHONPATH=%~dp0"
python -m uvicorn app.main:app --app-dir "%~dp0" --host 127.0.0.1 --port 9100 --reload
