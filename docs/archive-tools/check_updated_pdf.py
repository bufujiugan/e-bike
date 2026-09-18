from pathlib import Path
import re
import sys
from pypdf import PdfReader
import pdfplumber
from PIL import Image, ImageOps, ImageDraw

sys.stdout.reconfigure(encoding='utf-8')
base = Path('F:/bike/tmp/pdfs/after-sales-20260909')
target = Path('F:/bike/output/pdf/E-Bike生产库存管理系统_项目进度与功能清单_售后需求增补版_V1.1_20260909.pdf')
reader = PdfReader(target)
assert len(reader.pages) == 14
texts = [page.extract_text() for page in reader.pages]
identifiers = []
for index in [3,4,5,7,8]:
    identifiers.extend(int(value) for value in re.findall(r'^\d{1,2}$', texts[index], re.M))
assert identifiers == list(range(1,55)), identifiers
for index, text in enumerate(texts,1):
    assert f'{index:02d} / 14' in text
    assert '\ufffd' not in text
required = ['旧件处理', 'WhatsApp', 'Email', '最终客户购买日期', '邮政编码', '预计到达日期',
            '待提交', '审核中', '已审批', '处理中', '已发货', '保修', '采购意向', '售后负责人',
            '阶段待定', '后期扩展', '2026/10/21', '2026/11/6']
all_text = ''.join(texts).replace('\n','')
assert all(value in all_text for value in required)
with pdfplumber.open(target) as pdf:
    for i, page in enumerate(pdf.pages,1):
        outside = [c for c in page.chars if c['x0'] < 37 or c['x1'] > 558 or c['top'] < 25 or c['bottom'] > 822]
        assert not outside, (i,outside[:3])
for batch, numbers in enumerate([range(1,8),range(8,15)],1):
    sheet = Image.new('RGB',(4*442,2*646),'#e6edf2')
    draw = ImageDraw.Draw(sheet)
    for slot, number in enumerate(numbers):
        with Image.open(base/f'final-{number:02d}.png') as page_image:
            thumb = ImageOps.contain(page_image,(430,610))
            x,y=(slot%4)*442+6,(slot//4)*646+23
            sheet.paste(thumb,(x,y))
            draw.text((x,y-17),f'Page {number:02d}',fill='black')
    sheet.save(base/f'contact-final-{batch}.png')
print(f'PASS: 14 pages; IDs 1-54 complete; page numbers and required fields present; text within page bounds. {target.stat().st_size} bytes.')
