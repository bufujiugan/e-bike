"""生产验收补充：真实岗位权限、导入错误行及打印超时。"""
import production_smoke as api
from production_acceptance import ROOT,SCHEMA
import io,json,time,uuid
import pymysql
import openpyxl
import xml.etree.ElementTree as ET

api.TOKEN=api.request('POST','/auth/login',{'username':'admin','password':'Production-Review-2026!'},token='')['accessToken']
prefix='EDGE'+uuid.uuid4().hex[:6].upper()
roles=api.request('GET','/roles')
pda=next(r for r in roles if r['roleCode']=='PDA_OPERATOR')
sku=api.request('GET','/production/skus')[0]
t=api.task(sku['id'])
def operator(kind):
    station=api.request('POST','/workstations',{'workstationCode':prefix+kind,'workstationName':kind+'验收工位','workstationType':kind})
    username=(prefix+kind).lower()
    user=api.request('POST','/users',{'username':username,'password':'Acceptance-Station-2026!','displayName':kind+'验收','userType':'PDA_OPERATOR','workstationId':station['id']})
    api.request('PUT',f'/users/{user["id"]}/roles',{'roleIds':[pda['id']]})
    login=api.request('POST','/auth/login',{'username':username,'password':'Acceptance-Station-2026!'},token='')
    token=login['accessToken']
    if login['mustChangePassword']:
        api.request('PUT','/auth/password',{'currentPassword':'Acceptance-Station-2026!','newPassword':'Acceptance-Station-Review!'},token=token)
        token=api.request('POST','/auth/login',{'username':username,'password':'Acceptance-Station-Review!'},token='')['accessToken']
    return token

motorToken=operator('MOTOR')
codes={kind:prefix+'-'+kind for kind in ['FRAME','MOTOR','BATTERY','DISPLAY']}
for kind in ['MOTOR','BATTERY','DISPLAY']:api.part(codes[kind],kind)
api.request('POST',f'/production/tasks/{t["id"]}/scan',{'code':codes['BATTERY']},'WORKSTATION_FORBIDDEN',motorToken)
api.request('POST',f'/production/tasks/{t["id"]}/scan',{'code':codes['MOTOR']},token=motorToken)
api.request('POST','/production/skus',{'modelId':sku['modelId'],'skuCode':prefix,'productName':'非法改SKU','templateId':1,'enabled':True},'PERMISSION_DENIED',motorToken)
for kind in ['BATTERY','DISPLAY','FRAME']:
    token=operator(kind)
    result=api.request('POST',f'/production/tasks/{t["id"]}/scan',{'code':codes[kind],'expectedType':'FRAME' if kind=='FRAME' else None},token=token)
assert result['status']=='COMPLETED'
print('PASS 真实分工位权限、跨账号任务交接、车架工位完成最后一码')

workbook=openpyxl.Workbook();sheet=workbook.active
sheet.append(['车型ID','SKU编码','商品名称','规格','EAN','UPC','模板ID','启用'])
sheet.append([sku['modelId'],prefix+'-SKU','导入验收','','4006381333931','036000291452',1,'true'])
sheet.append([sku['modelId'],prefix+'-BAD','非法EAN','','123','',1,'true'])
buffer=io.BytesIO();workbook.save(buffer)
result=api.upload('/production/skus/import',buffer.getvalue())
assert result['successRows']==1 and result['failureRows']==1 and result['failures'][0]['rowNumber']==3,result
print('PASS SKU Excel逐行事务与错误行定位')

job='FIRST-'+t['id']
api.request('POST',f'/production/prints/{job}/claim',{'terminal':'acceptance-timeout'})
api.request('POST',f'/production/prints/{job}/result',{'status':'FAILED'},'PERMISSION_DENIED',motorToken)
with pymysql.connect(host='127.0.0.1',port=13316,user='root',database=SCHEMA) as connection:
    root=ET.parse(ROOT/'bike-server/src/test/resources/mapper/ProductionAcceptanceSetupMapper.xml').getroot()
    with connection.cursor() as cursor:cursor.execute(root.find("./update[@id='expireTestClaim']").text,(job,))
    connection.commit()
# 用正常轮询触发超时恢复，不修改业务表的结果状态。
claimed=api.request('POST','/production/prints/claim',{'terminal':'acceptance-timeout'})
assert api.request('GET',f'/production/prints/{job}')['status']=='UNKNOWN'
if claimed:api.request('POST',f'/production/prints/{claimed["id"]}/result',{'status':'FAILED','message':'验收轮询未实物打印'})
retry=api.request('POST',f'/production/prints/{job}/reprint',{'reason':'核对实物后补打'})
assert retry['status']=='PENDING'
api.request('POST',f'/production/prints/{job}/reprint',{'reason':'重复点击测试'},'PRINT_PENDING')
print('PASS 打印超时转待核对、非领取账号不能回报、授权补打')

issue=api.request('POST','/production/issues',{'code':codes['FRAME'],'reason':'隔离不得绕过旧组装'})
newTask=api.task(sku['id']);reservedCode=prefix+'-RESERVED';api.part(reservedCode,'MOTOR');api.scan(newTask['id'],reservedCode)
legacyFrame=prefix+'-LEGACY';api.request('POST','/vehicles',{'frameSn':legacyFrame,'modelId':sku['modelId']})
api.request('POST','/assembly/submit',{'frameSn':legacyFrame,'motorSn':reservedCode,'batterySn':codes['BATTERY'],'displaySn':codes['DISPLAY']},'PRODUCTION_RESERVED')
print('PASS 旧组装接口不能抢走生产任务预占部件')
summary=json.loads((ROOT/'output/production-acceptance/smoke-results.json').read_text(encoding='utf-8'))
vehicle=summary['vehicle'];customer=api.request('GET','/master/customers')[0]
productionSku=next(s for s in api.request('GET','/production/skus') if s['id']==vehicle['skuId'])
order=api.request('POST','/orders',{'customerId':customer['id'],'modelId':productionSku['modelId'],'orderQty':1,'remark':'仅验收旧出库隔离门禁，不涉及PI'})
api.request('POST',f'/orders/{order["id"]}/confirm')
quarantine=api.request('POST','/production/issues',{'code':vehicle['frameSn'],'reason':'出库门禁验收'})
api.request('POST','/stock-out',{'customerId':customer['id'],'orderId':order['id'],'vehicleQrCodes':[vehicle['qrCode']]},'PRODUCTION_QUARANTINED')
api.request('POST',f'/production/issues/{quarantine["id"]}/resolve',{'reason':'验收已结束'})
api.request('POST',f'/orders/{order["id"]}/cancel')
print('PASS 既有按订单出库受到生产隔离门禁保护')
(ROOT/'output/production-acceptance/boundary-results.json').write_text(json.dumps({'prefix':prefix,'checks':['工位权限与交接','Excel错误行','打印超时和越权回报','旧组装预占门禁'],'taskId':t['id'],'printId':retry['id']},ensure_ascii=False,indent=2),encoding='utf-8')
