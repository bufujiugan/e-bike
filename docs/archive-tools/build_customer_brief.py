from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4

ROOT = Path('F:/bike')
OUT = ROOT / 'output/pdf/E-Bike项目_客户沟通与谈判提纲_重点加粗版_20260914.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)
pdfmetrics.registerFont(TTFont('CN', 'C:/Windows/Fonts/msyh.ttc', subfontIndex=0))
pdfmetrics.registerFont(TTFont('CNB', 'C:/Windows/Fonts/msyhbd.ttc', subfontIndex=0))
pdfmetrics.registerFontFamily('CN', normal='CN', bold='CNB', italic='CN', boldItalic='CNB')
NAVY = colors.HexColor('#173449')
TEAL = colors.HexColor('#007D82')
INK = colors.HexColor('#263844')
MUTED = colors.HexColor('#617783')
PALE = colors.HexColor('#EFF6F7')
LINE = colors.HexColor('#D6E2E7')
W, H = A4
WIDTH = W - 92
styles = {
    'title': ParagraphStyle('title', fontName='CNB', fontSize=24, leading=34, textColor=NAVY, spaceAfter=12),
    'sub': ParagraphStyle('sub', fontName='CN', fontSize=10, leading=16, textColor=MUTED, spaceAfter=15),
    'body': ParagraphStyle('body', fontName='CN', fontSize=10, leading=17, textColor=INK, spaceAfter=8, wordWrap='CJK'),
    'head': ParagraphStyle('head', fontName='CNB', fontSize=12, leading=19, textColor=NAVY, spaceBefore=10, spaceAfter=5),
    'small': ParagraphStyle('small', fontName='CN', fontSize=8.5, leading=14, textColor=MUTED, spaceAfter=5, wordWrap='CJK'),
    'cell': ParagraphStyle('cell', fontName='CN', fontSize=9.2, leading=15, textColor=INK, wordWrap='CJK'),
    'cellhead': ParagraphStyle('cellhead', fontName='CNB', fontSize=9.2, leading=15, textColor=colors.white, wordWrap='CJK'),
    'quote': ParagraphStyle('quote', fontName='CN', fontSize=11, leading=19, textColor=NAVY, wordWrap='CJK'),
}
story = []

def p(text, style='body'):
    return Paragraph(text, styles[style])

def add(text, style='body'):
    story.append(p(text, style))

def section(title, text):
    story.append(KeepTogether([p(title, 'head'), p(text)]))

def callout(text):
    table = Table([[p(text, 'quote')]], colWidths=[WIDTH])
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), PALE),
        ('BOX', (0, 0), (-1, -1), .5, LINE),
        ('LINEBEFORE', (0, 0), (0, -1), 3, TEAL),
        ('LEFTPADDING', (0, 0), (-1, -1), 14),
        ('RIGHTPADDING', (0, 0), (-1, -1), 14),
        ('TOPPADDING', (0, 0), (-1, -1), 11),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 11),
    ]))
    story.extend([table, Spacer(1, 10)])

def grid(headers, rows, widths):
    data = [[p(x, 'cellhead') for x in headers]] + [[p(x, 'cell') for x in row] for row in rows]
    table = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), NAVY),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, PALE]),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 9),
        ('RIGHTPADDING', (0, 0), (-1, -1), 9),
        ('TOPPADDING', (0, 0), (-1, -1), 9),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 9),
        ('LINEBELOW', (0, 0), (-1, -1), .35, LINE),
    ]))
    story.extend([table, Spacer(1, 8)])

def page(num, title, subtitle):
    if story:
        story.append(PageBreak())
    add(f'{num:02d} / 客户沟通与谈判提纲', 'sub')
    add(title, 'title')
    add(subtitle, 'sub')


page(1, '客户买的不是扫码\n而是业务可控'.replace('\n', '<br/>'), 'E-Bike 生产库存与追溯系统  |  2026.09.14  |  内部会谈准备版')
callout('<b>让每台车的数据可信、流转受控、历史可查。</b><br/>减少装配和发货错误，提高库存可信度，缩短问题追查时间。')
add('项目以<b>车架 SN 为整车业务主线</b>，将电机、电池、仪表与整车绑定，连接生产、仓库、订单、代理商及返工记录。它是聚焦生产扫码、整车库存和追溯的系统，不是完整 ERP 或 MES。')
add('来料登记 → 四码绑定 → 整车建档 → 入库 → 锁库存／订单 → 出库 → 返工与追溯', 'small')
grid(['客户提出的功能', '希望解决的问题', '对客户表达的价值'], [
    ['四码绑定／整车码', '装错件、重复占用、身份混乱', '<b>一车一档，关键部件不能重复占用。</b>'],
    ['库存／出入库', '账上有货却找不到，重复承诺', '<b>分清在库、预留和还能卖的数量。</b>'],
    ['代理商锁库存', '反复询货，预留货源不确定', '<b>货源预留有规则、有期限、有记录。</b>'],
    ['订单关联出库', '发错车型、超量发货、归属不清', '<b>每台出库车辆对应客户和订单。</b>'],
    ['返工／双向追溯', '原装件和换件过程查不清', '<b>换件不抹掉历史，问题有据可查。</b>'],
    ['工位权限／日志', '操作边界不清，变更无证据', '<b>明确谁能操作，保留变更证据。</b>'],
], [113, 168, WIDTH - 281])
add('使用场景：Web 管理后台用于管理与查询；PDA/H5 用于现场扫码作业；代理商端用于授权库存查看与数量预留。', 'small')
add('专业提醒：扫码校验不等于实物装配检测；库存可信也依赖及时、完整的现场记录。不要承诺仅靠扫码就能杜绝所有差错。', 'small')

page(2, '读懂没有说出口的诉求', '以下为需求假设，不是已经证实的客户事实；用真实案例验证。')
section('01  老板：不再依赖少数人掌握情况', '可能的痛点：库存和订单都要问人，答复慢、口径不一致。<br/><b>验证问题：</b>“想知道一个车型还有多少可卖、一个订单发到哪一步，需要找几个人？多久能得到一致答案？”<br/><b>价值表达：把分散在个人手里的业务信息，变成可查询、可核对的共同依据。</b>')
section('02  现场人员：系统不要拖慢作业', '可能的顾虑：扫码增加步骤，扫错难纠正，网络故障影响生产。<br/><b>验证问题：</b>“能否跟一台车走完整个流程？每一步允许增加多少操作时间？错扫、漏扫和断网时怎么处理？”<br/><b>价值表达：让系统适应现场节拍，让一线人员愿意用、持续用。</b>')
section('03  销售与代理商：拿得到货，分配有规则', '可能的痛点：预留不算数、占货不下单、多个业务员争货。<br/><b>验证问题：</b>“锁货是采购意向还是确认预留？到期谁释放？转订单后如何继续保障货源？”<br/><b>价值表达：减少重复承诺，让货源预留和订单履约有明确规则。</b>')
section('04  售后与品质：出问题时拿得出证据', '可能的痛点：责任难判，查原装件和维修历史耗时。<br/><b>验证问题：</b>“最近一次质量问题，最难的是找到车辆、客户，还是判断部件和处理责任？花了多久？”<br/><b>价值表达：缩短查档时间，为质量处理和责任核实提供依据。</b>')
section('05  IT 与采购：数据自主，后续维护有保障', '可能的顾虑：被供应商绑定，人员变动后无人维护。<br/><b>验证问题：</b>“源码、部署权限、数据导出、备份恢复和后续维护，贵方希望分别掌握到什么程度？”<br/><b>价值表达：明确数据、交付物和维护责任，让长期使用更可控。</b>')
section('06  项目负责人：上线后能向管理层交代', '可能的顾虑：延期、部门不配合、上线没人用。<br/><b>验证问题：</b>“上线一个月后，您最希望向管理层证明哪三件事改善了？”<br/><b>价值表达：用可验收的业务结果，证明项目投入的价值。</b>')

page(3, '先问损失，再谈方案', '建议的会谈顺序：真实事件 → 影响规模 → 业务规则 → 优先级 → 验收。')
grid(['顺序', '可以直接问客户', '你要确认的内容'], [
    ['1 立项动因', '“最近发生了什么，让这个项目变得必须推进？”', '真正的触发事件和紧迫性'],
    ['2 第一痛点', '“第一期只能优先解决一个问题，您会选什么？”', '核心诉求，避免平均用力'],
    ['3 损失规模', '“一个月发生几次？需要多少人、多少时间处理？”', '损失基线与投入依据'],
    ['4 现场条件', '“每天多少台车、多少工位同时扫码？条码、设备和网络是什么情况？”', '容量、适配和上线条件'],
    ['5 异常规则', '“错扫、撤销、换件、退货和盘点差异，各由谁确认处理？”', '异常流程及责任人'],
    ['6 决策验收', '“谁确认业务规则？哪些部门试运行？谁最终签收？”', '决策人、使用人、验收人'],
    ['7 商务约束', '“何时必须上线？预算是否审批？有哪些前置条件？”', '预算、时间和外部依赖'],
], [74, 288, WIDTH - 362])
section('追问方法：让客户讲出一个真实案例', '不要停留在“库存不准”“流程太慢”。继续问：发生在哪一步？影响哪张订单？谁花了多久处理？<br/>如果不处理，会产生什么后果？<br/><b>一个真实案例，比十个泛泛的功能需求更能帮助确定方案。</b>')
section('成功指标：先有现状，再定目标', '可选择单车操作耗时、库存核对耗时、售后查档耗时、错发或超发次数等指标。先记录现状，明确统计口径，再约定试运行目标。<br/><b>不在没有基线时承诺“效率提升百分之多少”。</b>')
callout('<b>会谈结束前，至少确认四件事：</b><br/>最痛的问题是什么；第一期必须做到什么；由谁配合；用什么证据验收。')

page(4, '守住边界，才能守住交付', '已明确的项目口径与需要进一步确认的概念，必须分开讨论。')
section('已明确 01：锁车型数量，不锁具体车', '第一期按<b>整车车型＋数量</b>锁库存，不锁零部件，也不锁具体车辆 SN。<br/><b>客户话术：“数量预留解决货源承诺，具体车辆匹配解决实际发货，两者需要分清。”</b><br/>进一步确认：锁定到期、释放权限，以及转订单后到出库前的库存保障规则。')
section('已明确 02：扫码识别不等于直接放行', '普通出库必须先关联客户和订单，再扫描总二维码、逐台校验、批量确认。<br/><b>客户话术：“扫码识别车辆，客户和订单明确发货归属。可以优化操作步骤，但要保留业务关联。”</b>')
grid(['客户使用的词', '必须进一步澄清的边界'], [
    ['“售后管理”', '仅查询与换件历史，还是包含退货、维修工单、质保判定和费用结算？'],
    ['“代理商库存”', '查看工厂可预留库存，还是管理代理商收货、调拨和终端销售？'],
    ['“质量追溯”', '单个 SN 关系查询，还是批次召回、风险通知和供应商索赔？'],
    ['“支持打印”', '标签下载与普通打印，还是指定设备 SDK、自动打印和特殊标签模板？'],
], [107, WIDTH - 107])
section('本期明确不包含的扩展', '第三方 ERP/WMS/MES 对接；财务、采购系统、APS 排产；原生 Android/iOS APP、微信小程序；支付、在线合同；PLC 对接；打印机 SDK 深度集成；断网离线生产同步；多工厂集团架构。')
section('新增需求如何处理', '记录业务场景 → 明确规则与验收 → 评估影响 → 确认费用和工期 → 再进入实施。<br/><b>“预留扩展能力”不等于“本期已经包含实现和交付”。</b>')
add('口径依据：bike_codex_project_spec.md 第 17、14、49 节；docs/requirement-conflicts.md。完整售后及其他扩展如已有后续双方确认文件，应按最新签字版本另行核对。', 'small')

page(5, '用业务验收支撑报价', '谈价值、谈范围、谈证据，不用页面数量代替交付价值。')
section('客户压价时：调整阶段与范围', '<b>“可以根据预算分阶段推进。先确定第一期必须落地的业务闭环，再把扩展项单列。重复绑定拦截、库存一致性、权限隔离和历史记录这些基础保障，建议保留。”</b>')
section('报价和合同建议拆分', '分别写清软件范围、指定设备适配、数据初始化、培训试运行、部署维护及外部接口。客户负责提供设备、现场网络、基础资料和业务确认人；双方约定响应时间、变更流程和验收条件。<br/><b>每一项费用对应清晰的交付物，每一个里程碑对应可核验的结果。</b>')
grid(['验收场景', '应观察到的结果'], [
    ['两个工位抢同一部件', '<b>只能有一台车绑定成功</b>，另一操作得到明确失败原因。'],
    ['订单或车辆信息不匹配', '客户、订单、车型不匹配或超量时，<b>不能正常出库</b>。'],
    ['代理商并发竞争库存', '并发新增锁定总量不超过操作前可用数量，<b>不能超额预留</b>。'],
    ['换件后再查整车', '<b>原部件、新部件和更换记录均可查询</b>，历史不被覆盖。'],
    ['代理商跨账号访问', '<b>不能读取或操作其他代理商的受限数据</b>。'],
    ['指定设备连续作业', '在约定 PDA、扫码枪、标签打印和网络环境下，完成真实作业流程。'],
], [143, WIDTH - 143])
section('时间与付款：按里程碑确认', '可按需求基线确认、核心流程演示、现场试运行、最终验收组织阶段。具体日期、付款比例、缺陷处理标准和维护期限由双方协商，不依据演示完成就推定正式交付完成。')
callout('<b>软件功能已实现，不等于现场已经可交付。</b><br/>验收应使用客户真实样本、指定设备和约定业务场景，而不只是“页面能够打开”。')

page(6, '现场可以直接使用的话术', '粗体为优先表达内容；内部判断和验收提醒不作为已完成承诺。')
section('开场：先建立共同目标', '“我们理解，这个项目不只是扫码登记，而是要把每台车从组装、库存、销售到返工的记录串起来。<b>重点解决三件事：部件和车辆关系清楚，库存和发货数量可信，出问题后能够快速追查。</b><br/>在确认方案前，想先了解贵方当前损失最大的是生产装配、库存发货，还是售后追溯？我们先用一个真实案例把流程走通，再确定第一期优先级和验收标准。”')
section('客户问：跟 Excel 有什么区别？', '“Excel 可以记录数据。这套系统的重点是，<b>在操作发生时检查规则</b>，例如部件能否绑定、车辆能否出库、库存能否预留，并关联人员、时间和历史。最终要看这些控制能否减少实际差错和核对工作。”')
section('客户问：功能能不能都包含？', '“我们可以先分清哪些是本期必须解决的问题，哪些是扩展场景。<b>先把最影响经营的流程做实，再按优先级扩展。</b>涉及新流程、外部系统或设备深度对接的部分，单独确认规则、验收和费用，避免上线时产生理解差异。”')
section('结束：争取一个明确的下一步', '“今天先确认三项：<b>第一期优先解决什么、由谁确认业务规则、用什么场景验收。</b>如果方向一致，建议安排生产、仓库和销售一起，用真实样本走一遍流程，再确定需求基线和实施安排。”')
section('内部提醒：当前完成度应如何表达', '本提纲沿用前一轮对项目规格、业务规则、需求冲突和进度文档的核对结果。进度文档记录已有核心流程实现及此前的软件验证；代理商独立账号 HTTP 隔离、PDA 真机、扫码枪和打印机仍列为待专项验收。<b>本次仅制作沟通资料，未重新运行系统或进行现场验收。</b>')
add('资料依据：bike_codex_project_spec.md；README.md；docs/business-rules.md；docs/requirement-conflicts.md；docs/progress.md。本文不是合同、最终报价或新增需求确认书；后续增补范围须另核对双方最新确认版本。', 'small')
callout('<b>专业不是“什么都能做”，而是帮客户把损失、规则、责任和验收标准说清楚。</b>')

class NumberedCanvas(canvas.Canvas):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.saved_pages = []
    def showPage(self):
        self.saved_pages.append(dict(self.__dict__))
        self._startPage()
    def save(self):
        count = len(self.saved_pages)
        for state in self.saved_pages:
            self.__dict__.update(state)
            self.saveState()
            self.setStrokeColor(LINE)
            self.setLineWidth(.6)
            self.line(46, H-41, W-46, H-41)
            self.setFont('CNB', 8)
            self.setFillColor(TEAL)
            self.drawString(46, H-30, 'BIKE  /  BUSINESS BRIEF')
            self.setFont('CN', 8)
            self.setFillColor(MUTED)
            self.drawRightString(W-46, H-30, '客户沟通与谈判 · 内部准备资料')
            self.line(46, 43, W-46, 43)
            self.drawString(46, 28, '2026.09.14   |   加粗内容：价值表达与核心话术')
            self.drawRightString(W-46, 28, f'{self._pageNumber:02d} / {count:02d}')
            self.restoreState()
            super().showPage()
        super().save()

doc = SimpleDocTemplate(str(OUT), pagesize=A4, rightMargin=46, leftMargin=46,
                        topMargin=59, bottomMargin=57, title='E-Bike 项目客户沟通与谈判提纲',
                        author='Bike 项目', subject='客户核心诉求、隐性需求、范围边界与沟通话术')
doc.build(story, canvasmaker=NumberedCanvas)
print(OUT)
