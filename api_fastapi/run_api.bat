@echo off
python -m venv venv
call venv\Scripts\activate
pip install -r requirements.txt
if not exist .env copy .env.example .env
echo Edita .env con credenciales reales de MySQL RISEK y luego ejecuta:
echo uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
cmd /k
