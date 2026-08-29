import os
from contextlib import contextmanager

import mysql.connector
from dotenv import load_dotenv


load_dotenv()


def conn():
    return mysql.connector.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USER", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        database=os.getenv("DB_NAME", "risek"),
        charset="latin1",
        use_unicode=True,
        autocommit=False,
    )


@contextmanager
def cursor(dictionary=True):
    cn = conn()
    try:
        cur = cn.cursor(dictionary=dictionary)
        yield cn, cur
        cn.commit()
    except Exception:
        cn.rollback()
        raise
    finally:
        try:
            cur.close()
        except Exception:
            pass
        cn.close()
