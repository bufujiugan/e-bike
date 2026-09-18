from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from PIL import Image, ImageDraw, ImageFont, ImageOps
import sys

sys.stdout.reconfigure(encoding='utf-8')
root=Path('F:/bike/output/prototype')
shots=Path('F:/bike/output/playwright/ebike-hifi')
regular='C:/Windows/Fonts/msyh.ttc'
bold='C:/Windows/Fonts/msyhbd.ttc'
def font(size,heavy=False):return ImageFont.truetype(bold if heavy else regular,size)
labels=[
 ('01-admin-dashboard','公司后台 · 服务概览','工作台、趋势与服务待办'),
 ('03-case-detail','案例详情 · 协同处理','车辆、故障、附件与案例沟通'),
 ('02-case-list','售后案例 · 集中管理','筛选、查询与处理状态'),
 ('04-repair-plan','维修方案 · 技术确认','技术判断、维修步骤与换件建议'),
 ('14-customer-dashboard','客户门户 · 服务与支持','客户独立视角与案例进度'),
 ('08-warehouse','配件发运 · 售后闭环','批准方案、收货资料与物流登记'),
]
board=Image.new('RGB',(3120,2990),'#edf2ef')
d=ImageDraw.Draw(board)
d.rounded_rectangle((80,72,146,138),radius=16,fill='#17685a')
d.text((101,76),'E',font=font(43,True),fill='white')
d.text((173,66),'E-Bike 生产库存 · 售后服务平台',font=font(50,True),fill='#173b39')
d.text((174,137),'高保真交互原型  /  客户评审版 V1.0  /  2026.09.09',font=font(23),fill='#789083')
d.text((80,225),'让每一次服务，都有迹可循。',font=font(34,True),fill='#365d4d')
d.text((80,282),'6 个关键页面预览  ·  完整包包含 16 张独立界面图与可点击原型',font=font(22),fill='#7b9484')
for i,(name,title,subtitle) in enumerate(labels):
    x=80+(i%2)*1520;y=362+(i//2)*852
    d.text((x,y),f'{i+1:02d}',font=font(23,True),fill='#9bb4a3')
    d.text((x+52,y-5),title,font=font(27,True),fill='#325747')
    d.text((x+52,y+37),subtitle,font=font(19),fill='#8b9e8f')
    with Image.open(shots/(name+'.png')) as im:
        # 总览呈现每页的主要工作区，完整界面保留在独立 PNG 中。
        preview=im.crop((0,0,im.width,min(im.height,1080)))
        preview.thumbnail((1440,730),Image.Resampling.LANCZOS)
        d.rounded_rectangle((x,y+81,x+1440,y+816),radius=14,fill='white',outline='#d6e2d9',width=2)
        board.paste(preview,(x+(1440-preview.width)//2,y+84))
d.text((80,2930),'演示数据与方案预览，实际范围和业务规则以双方确认版本为准。',font=font(18),fill='#829787')
overview=root/'E-Bike_高保真原型总览.png'
board.save(overview,optimize=True)

# 全部截图组成校验页，不作为客户交付图。
names=sorted(shots.glob('*.png'))
assert len(names)==16,len(names)
contact=Image.new('RGB',(1920,1960),'#dfe7e2');cd=ImageDraw.Draw(contact)
for i,p in enumerate(names):
    x=(i%4)*480;y=(i//4)*490
    cd.text((x+8,y+7),p.stem,font=font(14),fill='#365d4d')
    with Image.open(p) as im:
        im.verify()
    with Image.open(p) as im:
        thumb=ImageOps.contain(im,(462,450))
        contact.paste(thumb,(x+9,y+34))
contact.save('F:/bike/tmp/pdfs/after-sales-20260909/prototype-contact.png')

archive=root/'E-Bike_高保真原型与界面图.zip'
with ZipFile(archive,'w',ZIP_DEFLATED) as z:
    for name in ['E-Bike_高保真交互原型.html','E-Bike_高保真原型总览.png','使用说明.md']:
        z.write(root/name,name)
    for p in names:z.write(p,'高清界面图/'+p.name)
    for name in ['index.html','prototype.css','prototype.js']:
        z.write(root/name,'原型源文件/'+name)
with ZipFile(archive) as z:
    assert z.testzip() is None
    assert len(z.namelist())==22
print('总览 PNG 已生成；16 张截图可解码；交付 ZIP 22 个文件完整性检查通过。')
print(archive)
