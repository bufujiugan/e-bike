"""只向本机隔离验收服务写入带随机前缀的测试数据。"""
import concurrent.futures
import itertools
import json
import urllib.request
import urllib.error
import urllib.parse
import uuid
import base64
import sys
from pathlib import Path

BASE='http://127.0.0.1:18080'
TOKEN=''
PREFIX='AC'+uuid.uuid4().hex[:8].upper()
RESULTS=[]

def request(method,path,body=None,expected='SUCCESS',token=None):
    headers={'Content-Type':'application/json'}
    if token is None: token=TOKEN
    if token: headers['Authorization']='Bearer '+token
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(BASE+'/api'+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as response: result=json.load(response)
    except urllib.error.HTTPError as error:
        result=json.load(error)
    if expected is not None: assert result['code']==expected,(path,result)
    return result.get('data') if expected=='SUCCESS' else result

def check(name, action):
    action();RESULTS.append(name);print('PASS '+name,flush=True)

def part(code,type):
    return request('POST','/parts',{'partSn':code,'partType':type,'qcStatus':'PASSED'})

def task(sku):
    return request('POST','/production/tasks',{'requestId':str(uuid.uuid4()),'skuId':sku,'batchNo':PREFIX})

def scan(id,code,type=None,expected='SUCCESS'):
    return request('POST',f'/production/tasks/{id}/scan',{'code':code,'expectedType':type},expected)

def upload(path, content, expected='SUCCESS', token=None):
    boundary='BikeImageBoundary'
    data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="evidence.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode())
    headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Authorization':'Bearer '+(TOKEN if token is None else token)}
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE+'/api'+path,data=data,headers=headers),timeout=30) as response: result=json.load(response)
    except urllib.error.HTTPError as error: result=json.load(error)
    assert result['code']==expected,result
    return result.get('data')

def main():
    global TOKEN
    # 仅此隔离库使用公开验收口令，正式部署必须自行配置。
    login=request('POST','/auth/login',{'username':'admin','password':'Production-Accept-2026!'},expected=None,token='')
    if login['code']=='SUCCESS':
        TOKEN=login['data']['accessToken']
        request('PUT','/auth/password',{'currentPassword':'Production-Accept-2026!','newPassword':'Production-Review-2026!'})
    login=request('POST','/auth/login',{'username':'admin','password':'Production-Review-2026!'},token='')
    TOKEN=login['accessToken']
    skus=request('GET','/production/skus');sku=skus[0]['id']
    completed=[]
    for i,order in enumerate(itertools.permutations(['FRAME','MOTOR','BATTERY','DISPLAY'])):
        codes={kind:f'{PREFIX}-{i}-{kind}' for kind in order}
        for kind,code in codes.items():
            if kind!='FRAME': part(code,kind)
        t=task(sku)
        for kind in order:
            latest=scan(t['id'],codes[kind],'FRAME' if kind=='FRAME' else None)
        assert latest['status']=='COMPLETED',latest
        original=request('GET',f'/production/prints/FIRST-{t["id"]}')
        assert original['status']=='PENDING'
        repeated=scan(t['id'],codes[order[-1]])
        assert repeated['qrCode']==latest['qrCode']
        request('POST',f'/production/tasks/{t["id"]}/complete')
        completed.append((latest,codes))
    RESULTS.append('24种扫码顺序、车架最后、重复完成、唯一聚合码及首打');print('PASS '+RESULTS[-1],flush=True)
    def reservation():
        code=PREFIX+'-SHARED';part(code,'MOTOR');a,b=task(sku),task(sku)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            outcomes=list(pool.map(lambda t:scan(t['id'],code,expected=None),[a,b]))
        assert sorted(r['code'] for r in outcomes)==['PRODUCTION_RESERVED','SUCCESS'],outcomes
        winner=a if outcomes[0]['code']=='SUCCESS' else b;loser=b if winner==a else a
        request('POST',f'/production/tasks/{winner["id"]}/cancel',{'reason':'验收取消释放'})
        scan(loser['id'],code)
    check('并发抢件仅一个成功，取消后释放',reservation)
    def rollback():
        t=task(sku);codes={kind:PREFIX+'-ROLLBACK-'+kind for kind in ['FRAME','MOTOR','BATTERY','DISPLAY']}
        for kind in ['MOTOR','BATTERY','DISPLAY']:
            part(codes[kind],kind);scan(t['id'],codes[kind])
        issue=request('POST','/production/issues',{'code':codes['MOTOR'],'reason':'最终提交前隔离'})
        scan(t['id'],codes['FRAME'],'FRAME','PRODUCTION_QUARANTINED')
        restored=request('GET',f'/production/tasks/{t["id"]}')
        assert len(restored['scans'])==3 and restored['status']=='OPEN'
        request('GET',f'/production/prints/FIRST-{t["id"]}',expected='PRODUCTION_NOT_FOUND')
        request('POST',f'/production/issues/{issue["id"]}/resolve',{'reason':'复检通过'})
        assert scan(t['id'],codes['FRAME'],'FRAME')['status']=='COMPLETED'
    check('第四码失败整笔回滚、前三码保留、解除后重试成功',rollback)
    vehicle,codes=completed[0]
    issue=request('POST','/production/issues',{'code':codes['FRAME'],'reason':'验收质量隔离'})
    queueJob=request('POST','/production/prints/claim',{'terminal':'skip-quarantine'})
    assert queueJob['vehicleId']!=vehicle['vehicleId']
    request('POST',f'/production/prints/{queueJob["id"]}/result',{'status':'FAILED','message':'接口验收未打印'})
    warehouse=request('GET','/master/warehouses')[0]
    location=request('GET',f'/master/warehouses/{warehouse["id"]}/locations')[0]
    stock={'vehicleQrCode':vehicle['qrCode'],'warehouseId':warehouse['id'],'locationId':location['id']}
    check('隔离阻止入库',lambda:request('POST','/stock-in',stock,'PRODUCTION_QUARANTINED'))
    check('隔离阻止指定标签领取',lambda:request('POST',f'/production/prints/FIRST-{vehicle["id"]}/claim',{'terminal':'acceptance'},'PRODUCTION_QUARANTINED'))
    # 合法 PNG 与伪装图片；访问必须鉴权。
    png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=')
    attachment=upload(f'/production/issues/{issue["id"]}/images',png)
    upload(f'/production/skus/{sku}/images',png)
    check('伪装图片拒绝',lambda:upload(f'/production/issues/{issue["id"]}/images',b'not an image','IMAGE_INVALID'))
    check('图片未登录不可读',lambda:request('GET',f'/production/images/{attachment["id"]}',expected='UNAUTHORIZED',token=''))
    request('POST',f'/production/issues/{issue["id"]}/resolve',{'reason':'已复检合格'})
    request('POST','/stock-in',stock)
    check('解除隔离后可入库且重复入库被阻止',lambda:request('POST','/stock-in',stock,'VEHICLE_ALREADY_STOCKED_IN'))
    inventory=request('GET',f'/production/inventory?skuId={sku}&warehouseId={warehouse["id"]}')
    assert inventory and inventory[0]['physicalQty']>=1
    new=PREFIX+'-REPLACED-MOTOR';part(new,'MOTOR')
    request('POST','/rework',{'frameSn':codes['FRAME'],'partType':'MOTOR','newPartSn':new,'reason':'验收厂内换件'})
    label=request('GET',f'/production/prints/FIRST-{vehicle["id"]}')
    assert label['status']=='FAILED'
    reprint=request('POST',f'/production/prints/{label["id"]}/reprint',{'reason':'换件后核对重打'})
    snapshot=json.loads(reprint['snapshot']);assert snapshot['motorSn']==new and snapshot['qrCode']==vehicle['qrCode']
    t=task(sku);scan(t['id'],codes['MOTOR'],expected='PART_NOT_AVAILABLE')
    request('POST',f'/production/quarantined-parts/{codes["MOTOR"]}/release',{'reason':'维修后复检合格'})
    scan(t['id'],codes['MOTOR'])
    check('换件保留聚合码，旧标签失效，新标签含新件；旧件必须复检',lambda:None)
    request('GET',f'/trace/part/{codes["MOTOR"]}')
    trace=request('GET','/trace/qr/'+urllib.parse.quote(vehicle['qrCode'],safe=''))
    assert trace['vehicle']['motorSn']==new
    assert any(event['type']=='PRODUCTION' for event in trace['events'])
    job=reprint['id'];request('POST',f'/production/prints/{job}/claim',{'terminal':'acceptance'})
    request('POST',f'/production/prints/{job}/claim',{'terminal':'second'},'PRINT_ALREADY_CLAIMED')
    request('POST',f'/production/prints/{job}/result',{'status':'SUBMITTED','message':'接口验收模拟回报，未实物打印'})
    request('POST',f'/production/prints/{job}/result',{'status':'SUBMITTED','message':'接口验收模拟回报，未实物打印'})
    check('打印领取互斥及回报幂等、总码和旧件追溯',lambda:None)
    output=Path(__file__).resolve().parents[1]/'output/production-acceptance'
    output.mkdir(parents=True,exist_ok=True)
    (output/'smoke-results.json').write_text(json.dumps({'prefix':PREFIX,'checks':RESULTS,'vehicle':vehicle,'printId':job},ensure_ascii=False,indent=2),encoding='utf-8')
    print('生产接口验收通过，未执行实物打印。')

if __name__=='__main__':main()
