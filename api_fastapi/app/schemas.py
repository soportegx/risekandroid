from pydantic import BaseModel
from typing import Optional, List

class LoginRequest(BaseModel):
    sec_user_id:int
    password:str
class LoginResponse(BaseModel):
    ok:bool
    token:Optional[str]=None
    vendedor_codigo:Optional[str]=None
    message:Optional[str]=None
class NvLine(BaseModel):
    producto_codigo:str
    descripcion:Optional[str]=None
    uxe:float=0
    cantidad:float=0
    precio:int=0
    descuento:float=0
    neto_linea:int=0
    iva_linea:int=0
    ila_linea:int=0
    total_linea:int=0
    bodega_codigo:Optional[str]='01'
class NvSyncRequest(BaseModel):
    offline_id:str
    local_codigo:str='01'
    bodega_codigo:str='01'
    cliente_rut:str
    vendedor_codigo:Optional[str]=None
    venta_fecha:str
    venta_fechavto:str
    venta_direccion:Optional[str]=None
    venta_neto:int=0
    venta_iva:int=0
    venta_ila:int=0
    venta_totalventa:int
    venta_observacion01:Optional[str]=None
    venta_guardado_ms:Optional[int]=None
    lines:List[NvLine]
class NvSyncResponse(BaseModel):
    ok:bool
    venta_numero:Optional[int]=None
    already_synced:bool=False
    message:Optional[str]=None


class NvDeleteRequest(BaseModel):
    offline_id:str
    venta_numero:Optional[int]=None
    local_codigo:str='01'

class NvDeleteResponse(BaseModel):
    ok:bool
    venta_numero:Optional[int]=None
    message:Optional[str]=None
