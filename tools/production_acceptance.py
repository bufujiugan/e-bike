"""生产验收使用独立数据库；不修改默认业务库，不输出连接凭据。"""
import os
import re
import sys
import subprocess
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path
import pymysql
import yaml

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = 'bike_production_acceptance_20260916'

def resolved(value):
    match = re.fullmatch(r'\$\{([^:}]+)(?::(.*))?}', str(value))
    return os.environ.get(match[1], match[2] or '') if match else value

def database_config():
    if os.environ.get('BIKE_ACCEPT_LOCAL') == 'true':
        return urllib.parse.urlsplit('mysql://127.0.0.1:13316/'+SCHEMA), 'root', ''
    config = yaml.safe_load((ROOT/'bike-server/src/main/resources/application.yml').read_text(encoding='utf-8'))
    ds = config['spring']['datasource']
    url = urllib.parse.urlsplit(resolved(ds['url']).removeprefix('jdbc:'))
    return url, resolved(ds['username']), resolved(ds['password'])

def setup():
    url, username, password = database_config()
    connection = pymysql.connect(host=url.hostname, port=url.port or 3306, user=username, password=password, charset='utf8mb4', connect_timeout=10)
    with connection:
        with connection.cursor() as cursor:
            node=ET.parse(ROOT/'bike-server/src/test/resources/mapper/ProductionAcceptanceSetupMapper.xml').getroot().find("./update[@id='createDatabase']")
            cursor.execute(node.text)
    print('独立验收数据库已准备：'+SCHEMA)

def audit():
    url, username, password = database_config()
    with pymysql.connect(host=url.hostname,port=url.port or 3306,user=username,password=password,database=SCHEMA,charset='utf8mb4') as connection:
        with connection.cursor(pymysql.cursors.DictCursor) as cursor:
            root=ET.parse(ROOT/'bike-server/src/test/resources/mapper/ProductionAcceptanceSetupMapper.xml').getroot()
            cursor.execute(root.find("./select[@id='schemaAudit']").text)
            print(cursor.fetchone())

def start():
    url, username, password = database_config()
    env = os.environ.copy()
    env.update(BIKE_DB_URL=f'jdbc:mysql://{url.hostname}:{url.port or 3306}/{SCHEMA}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai', BIKE_DB_USERNAME=username, BIKE_DB_PASSWORD=password,
               BIKE_SERVER_PORT='18080', SERVER_ADDRESS='127.0.0.1', BIKE_ADMIN_PASSWORD='Production-Accept-2026!', BIKE_JWT_SECRET='production-acceptance-isolated-environment-secret-20260916',
               BIKE_FLYWAY_LOCATIONS='classpath:db/migration', BIKE_PRODUCTION_FILE_ROOT=str(ROOT/'output/production-acceptance/images'))
    # 环境变量只进入子进程；默认业务配置和口令不打印。
    log=ROOT/'output/production-acceptance/server.log';log.parent.mkdir(parents=True,exist_ok=True)
    with log.open('a',encoding='utf-8') as output:
        subprocess.run(['java','-jar',str(ROOT/'bike-server/target/bike-server-0.1.0-SNAPSHOT.jar')], cwd=ROOT/'bike-server', env=env, stdout=output,stderr=output,check=True)

if __name__ == '__main__':
    try:
        {'setup': setup, 'start': start, 'audit':audit}[sys.argv[1]]()
    except pymysql.MySQLError as error:
        print('数据库准备失败，错误编号：',error.args[0])
        sys.exit(1)
