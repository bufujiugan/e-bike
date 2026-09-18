"""生成独立运行配置；已有配置不覆盖，凭据不输出到控制台。"""
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
config = (root / '.env.example').read_text(encoding='utf-8')
for name in ('BIKE_DB_PASSWORD', 'BIKE_MYSQL_ROOT_PASSWORD', 'BIKE_JWT_SECRET', 'BIKE_ADMIN_PASSWORD'):
    config = config.replace(f'{name}=CHANGE_ME', f'{name}={secrets.token_hex(32)}')
try:
    with (root / '.env').open('x', encoding='utf-8', newline='\n') as output:
        output.write(config)
except FileExistsError:
    raise SystemExit('.env 已存在，未覆盖。请按 .env.example 检查已有配置。')
print('已生成 .env。管理员为 admin，初始密码请在本机 .env 中查看。请勿提交或分享该文件。')
