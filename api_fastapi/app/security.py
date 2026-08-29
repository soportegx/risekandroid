import os, datetime, jwt
from fastapi import Header, HTTPException
SECRET=os.getenv('JWT_SECRET','cambiar_esta_clave')

def create_token(sec_user_id:int, vendedor_codigo:str|None):
    payload={'sub':str(sec_user_id),'vendedor_codigo':vendedor_codigo,'exp':datetime.datetime.utcnow()+datetime.timedelta(days=7)}
    return jwt.encode(payload, SECRET, algorithm='HS256')

def current_user(authorization: str = Header(default='')):
    if not authorization.startswith('Bearer '):
        raise HTTPException(401, 'Token requerido')
    token=authorization.split(' ',1)[1]
    try:
        return jwt.decode(token, SECRET, algorithms=['HS256'])
    except Exception:
        raise HTTPException(401, 'Token inválido')
