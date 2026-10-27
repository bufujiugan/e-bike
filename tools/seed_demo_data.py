"""向当前本机 BIKE 库插入可识别的演示数据；SQL 集中在 DemoDataMapper.xml。"""
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlsplit

import pymysql

ROOT = Path(__file__).resolve().parents[1]
BATCH = 'MOCK-20260930'
REMARK = BATCH + ' 演示数据，非真实生产记录'
STATEMENTS = {node.attrib['id']: re.sub(r'#\{(\w+)\}', r'%(\1)s', node.text.strip())
              for node in ET.parse(ROOT / 'tools/demo-data/DemoDataMapper.xml').getroot()}


def connect():
    config = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                  if re.match(r'^[A-Z][A-Z0-9_]*=', line))
    url = urlsplit(config['BIKE_DB_URL'].removeprefix('jdbc:'))
    if (url.hostname, url.port, url.path) != ('127.0.0.1', 13319, '/bike'):
        raise RuntimeError('仅允许当前本机 127.0.0.1:13319/bike，拒绝写入其他数据库')
    return pymysql.connect(host=url.hostname, port=url.port, database='bike', user=config['BIKE_DB_USERNAME'],
                           password=config['BIKE_DB_PASSWORD'], charset='utf8mb4', autocommit=False,
                           cursorclass=pymysql.cursors.DictCursor, connect_timeout=10)


def run(apply):
    counts = Counter()
    with connect() as connection, connection.cursor() as cursor:
        def execute(statement, **params):
            try:
                cursor.execute(STATEMENTS[statement], params)
            except pymysql.MySQLError:
                print('SQL statement failed: '+statement, file=sys.stderr)
                raise
            if statement.startswith('insert'):
                counts[statement] += cursor.rowcount
            return cursor.lastrowid

        def query(statement, **params):
            execute(statement, **params)
            return cursor.fetchall()

        def audit(kind, business_id, action, at, frame=None):
            execute('insertAudit', businessType=kind, businessId=str(business_id), action=action,
                    frameSn=frame, operationAt=at, **operator_params)

        environment = query('selectEnvironment')[0]
        assert environment['database_name'] == 'bike' and environment['port'] == 13319
        operator = query('selectOperator')[0]
        template = query('selectTemplate')[0]
        operator_params = dict(operatorId=operator['id'], operator=operator['username'], remark=REMARK)
        now = environment['database_time']
        anchor = now - timedelta(hours=2)
        expires = now + timedelta(days=30)
        scope = dict(batch=BATCH, pattern=BATCH + '-%')
        locked = list(query('acquireLock', lockName=BATCH)[0].values())[0]
        if locked != 1:
            raise RuntimeError('演示批次正在由其他进程处理')
        try:
            if list(query('countBatch', **scope)[0].values())[0]:
                result = {r['name']: r['quantity'] for r in query('selectCounts', **scope)}
                print(json.dumps({'status': 'existing_batch_unchanged', 'batch': BATCH, 'counts': result}, ensure_ascii=False))
                return
            customers = [execute('insertCustomer', code=f'{BATCH}-CUS-{i+1}', name=f'演示·{name}')
                         for i, name in enumerate(['城市骑行贸易', '山地动力经销', '绿色出行科技'])]
            supplier = execute('insertSupplier', code=BATCH+'-SUP', name='演示·核心部件供应商')
            locations = []
            for w, name in enumerate(['华东成品仓', '华南配送仓', '出口备货仓']):
                warehouse = execute('insertWarehouse', code=f'{BATCH}-WH-{w+1}', name='演示·'+name)
                for zone in ['A', 'B']:
                    location = execute('insertLocation', warehouseId=warehouse, code=f'{BATCH}-W{w+1}-{zone}', name=zone+'区整车库位')
                    locations.append((warehouse, location))
            skus = []
            for m, name in enumerate(['城市通勤', '山地越野', '折叠轻骑', '长续航旅行']):
                model = execute('insertModel', code=f'{BATCH}-MODEL-{m+1}', name='演示·'+name)
                for color in ['海湾蓝', '石墨灰']:
                    code = f'{BATCH}-SKU-{len(skus)+1:02}'
                    spec = color+' / 48V 20Ah'
                    sku_id = execute('insertSku', modelId=model, code=code, name='演示·'+name+'电助力车', specification=spec, templateId=template['id'])
                    skus.append(dict(id=sku_id, model=model, code=code, name='演示·'+name+'电助力车', modelName='演示·'+name, spec=spec))
            for s, sku in enumerate(skus):
                customer = customers[s % len(customers)]
                orders = []
                created = anchor - timedelta(days=8)
                # 每个 SKU 有已交付、部分交付、待出库三种订单；占用头和SKU明细数量一致。
                for order_type, quantity, shipped, status in [('DONE', 1, 1, 'SHIPPED'), ('PART', 2, 1, 'PARTIAL_SHIPPED'), ('WAIT', 1, 0, 'CONFIRMED')]:
                    order_no = f'{BATCH}-SO-{s+1:02}-{order_type}'
                    order_id = execute('insertOrder', orderNo=order_no, customerId=customer, shipDate=(now+timedelta(days=3)).date(), status=status, createdAt=created, **operator_params)
                    execute('insertOrderItem', orderId=order_id, modelId=sku['model'], quantity=quantity, shipped=shipped, createdAt=created)
                    lock_id = execute('insertLock', lockNo=f'{BATCH}-LK-{s+1:02}-{order_type}', modelId=sku['model'], skuId=sku['id'], customerId=customer, quantity=quantity, converted=quantity, status='CONVERTED', expiresAt=expires, createdAt=created, **operator_params)
                    execute('insertReservation', orderId=order_id, lockId=lock_id, requestId=order_no, modelId=sku['model'], quantity=quantity, expiresAt=expires, createdAt=created)
                    execute('insertReservationItem', orderId=order_id, skuId=sku['id'], quantity=quantity, shipped=shipped)
                    audit('ORDER', order_id, '演示订单初始化', created)
                    orders.append((order_id, order_no))
                execute('insertLock', lockNo=f'{BATCH}-LK-{s+1:02}-TEMP', modelId=sku['model'], skuId=sku['id'], customerId=customer, quantity=1, converted=0, status='LOCKED', expiresAt=expires, createdAt=anchor, **operator_params)
                for j in range(8):
                    sequence = s*8+j+1
                    task = f'{BATCH}-TASK-{sequence:03}'
                    frame = f'{BATCH}-FRAME-{sequence:03}'
                    assembled = anchor-timedelta(days=sequence % 7, minutes=60)
                    inbound = assembled+timedelta(minutes=20) if j < 7 else None
                    outbound = assembled+timedelta(minutes=40) if j in (5, 6) else None
                    status = 3 if outbound else 2 if inbound else 1
                    warehouse, location = locations[(s+j) % len(locations)]
                    order_id, order_no = orders[j-5] if outbound else (None, None)
                    token = f'{BATCH}-QR-{sequence:03}'
                    updated = outbound or inbound or assembled
                    vehicle = execute('insertVehicle', frameSn=frame, modelId=sku['model'], status=status, qrToken=token, assembledAt=assembled,
                                      warehouseId=warehouse if inbound else None, locationId=location if inbound else None,
                                      stockInAt=inbound, stockOutAt=outbound, customerId=customer if outbound else None,
                                      orderNo=order_no, createdAt=assembled-timedelta(minutes=20), updatedAt=updated, **operator_params)
                    qr = f'BIKE:{vehicle}:{token}'
                    execute('setInsertedVehicleQr', qrCode=qr, vehicleId=vehicle, frameSn=frame, updatedAt=updated)
                    assert cursor.rowcount == 1
                    execute('insertTask', taskId=task, skuId=sku['id'], batch=BATCH, status='COMPLETED', vehicleId=vehicle,
                            createdAt=assembled-timedelta(minutes=20), updatedAt=assembled, **operator_params)
                    execute('insertProductionVehicle', vehicleId=vehicle, taskId=task, skuId=sku['id'], batch=BATCH)
                    codes = {'FRAME': frame}
                    for k, part_type in enumerate(['FRAME', 'MOTOR', 'BATTERY', 'DISPLAY']):
                        code = frame if part_type == 'FRAME' else f'{BATCH}-{part_type}-{sequence:03}'
                        codes[part_type] = code
                        execute('insertScan', taskId=task, type=part_type, code=code, scannedAt=assembled-timedelta(minutes=4-k), active=0, **operator_params)
                        if part_type != 'FRAME':
                            part = execute('insertPart', code=code, type=part_type, supplierId=supplier, batch=BATCH, qcStatus='PASSED', bindStatus='BOUND', vehicleId=vehicle, frameSn=frame, status='IN_USE', inboundAt=assembled-timedelta(days=1), **operator_params)
                            execute('insertBinding', vehicleId=vehicle, frameSn=frame, partId=part, code=code, type=part_type, boundAt=assembled, **operator_params)
                    audit('ASSEMBLE', task, '演示四码组装完成', assembled, frame)
                    if inbound:
                        stock_no = f'{BATCH}-IN-{sequence:03}'
                        execute('insertStockIn', stockNo=stock_no, vehicleId=vehicle, warehouseId=warehouse, locationId=location, operationAt=inbound, **operator_params)
                        audit('STOCK_IN', stock_no, '演示整车入库', inbound, frame)
                    if outbound:
                        stock_no = f'{BATCH}-OUT-{sequence:03}'
                        record = execute('insertStockOut', stockNo=stock_no, vehicleId=vehicle, customerId=customer, orderId=order_id, operationAt=outbound, **operator_params)
                        execute('insertOrderVehicle', orderId=order_id, vehicleId=vehicle, recordId=record, createdAt=outbound)
                        audit('STOCK_OUT', stock_no, '演示订单出库', outbound, frame)
                    if j == 0 and s % 2 == 0:
                        execute('insertIssue', issueId=f'{BATCH}-ISSUE-{s+1}', code=frame, reason=REMARK+'：外观待复检', createdAt=inbound+timedelta(minutes=5), **operator_params)
                    # 不创建可被打印客户端领取的任务；所有快照明确为演示失败记录。
                    snapshot = dict(frameSn=frame, motorSn=codes['MOTOR'], batterySn=codes['BATTERY'], displaySn=codes['DISPLAY'], qrCode=qr,
                                    skuCode=sku['code'], modelName=sku['modelName'], productName=sku['name'], specification=sku['spec'], ean=None, upc=None,
                                    batchNo=BATCH, templateName=template['name'], templateVersion=template['version'], widthMm=template['width_mm'], heightMm=template['height_mm'], layout=template['layout'], heading=template['heading'])
                    execute('insertPrint', printId=f'{BATCH}-PRINT-{sequence:03}', vehicleId=vehicle, snapshot=json.dumps(snapshot, ensure_ascii=False), createdAt=assembled, **operator_params)
            for j in range(20):
                sku = skus[j % len(skus)]
                task = f'{BATCH}-TASK-{65+j:03}'
                cancelled = j >= 16
                started = anchor-timedelta(minutes=30-j)
                execute('insertTask', taskId=task, skuId=sku['id'], batch=BATCH, status='CANCELLED' if cancelled else 'OPEN', vehicleId=None, createdAt=started, updatedAt=started+timedelta(minutes=5), **operator_params)
                for k, part_type in enumerate(['FRAME', 'MOTOR', 'BATTERY'][:j % 4]):
                    code = f'{BATCH}-{part_type}-WIP-{j+1:02}'
                    execute('insertScan', taskId=task, type=part_type, code=code, scannedAt=started+timedelta(minutes=k), active=0 if cancelled else 1, **operator_params)
                    if part_type != 'FRAME':
                        execute('insertPart', code=code, type=part_type, supplierId=supplier, batch=BATCH, qcStatus='PASSED', bindStatus='UNBOUND', vehicleId=None, frameSn=None, status='AVAILABLE', inboundAt=started-timedelta(days=1), **operator_params)
                if j < 4:
                    execute('insertFailure', taskId=task, code=f'{BATCH}-UNREGISTERED-{j+1}', createdAt=started, **operator_params)
            for j, status in enumerate(['DRAFT', 'DRAFT', 'CANCELLED', 'CANCELLED', 'CLOSED', 'CLOSED']):
                sku = skus[j]
                order = execute('insertOrder', orderNo=f'{BATCH}-SO-EXTRA-{j+1}', customerId=customers[j % 3], shipDate=(now+timedelta(days=j+1)).date(), status=status, createdAt=anchor+timedelta(minutes=j), **operator_params)
                execute('insertOrderItem', orderId=order, modelId=sku['model'], quantity=2, shipped=0, createdAt=anchor)
            for j in range(6):
                execute('insertPart', code=f'{BATCH}-OLD-{j+1}', type=['MOTOR','BATTERY','DISPLAY'][j % 3], supplierId=supplier, batch=BATCH, qcStatus='PENDING', bindStatus='UNBOUND', vehicleId=None, frameSn=None, status='QUARANTINED', inboundAt=anchor-timedelta(days=2), **operator_params)
            violations = query('selectConsistencyViolations', **scope)
            if any(row['violations'] for row in violations):
                raise RuntimeError('关联一致性校验失败：'+json.dumps(violations, ensure_ascii=False))
            result = {row['name']: row['quantity'] for row in query('selectCounts', **scope)}
            expected = dict(models=4, skus=8, tasks=84, vehicles=64, parts=213, bindings=192, stockIn=56, stockOut=16, orders=30, locks=32, issues=4, prints=64, scanFailures=4)
            if result != expected:
                raise RuntimeError('批次数量不符合预期：'+json.dumps(result))
            if apply:
                connection.commit()
            else:
                connection.rollback()
            print(json.dumps({'status': 'committed' if apply else 'dry_run_rolled_back', 'batch': BATCH,
                              'counts': result, 'insertedRows': sum(counts.values()), 'consistency': violations}, ensure_ascii=False, default=str))
        except Exception:
            connection.rollback()
            raise
        finally:
            execute('releaseLock', lockName=BATCH)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='提交当前本机数据库；不传则完整演练后回滚')
    args = parser.parse_args()
    try:
        run(args.apply)
    except pymysql.MySQLError as error:
        # 不输出驱动的连接信息或完整SQL参数；错误编号可用于定位。
        print(f'数据库操作失败并回滚，MySQL错误编号：{error.args[0]}', file=sys.stderr)
        sys.exit(1)
