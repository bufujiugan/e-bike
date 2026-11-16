<template>
  <section class="europe-guide">
    <h3>先选择交易方式</h3>
    <p>销售与交付 → 欧洲销售与交付。普通外销 PI 也可从“PI 与履约”进入；欧洲页将三种业务集中展示。</p>
    <div class="guide-links"><a href="/europe-orders" target="_blank" rel="noopener noreferrer">打开欧洲销售与交付 ↗</a><a href="/pis" target="_blank" rel="noopener noreferrer">打开 PI 与履约 ↗</a></div>
    <article><h4>① 正常 PI 销售</h4><p>选择实际欧洲出货仓 → 新建 PI → 选择付款、交付配置 → 填写并确认 PI → 备货与交付跟进。</p><ul><li>先收款：按 PI 约定收款，再安排交付；货到付款：按约定先交付，再跟进收款。</li><li>欧洲入口新建 PI 默认“简化办理”。各环节正常填写，点击“确认并下一步”；合同、附件和款项资料可后补。</li><li>客户实际到账在“收款与放行”登记；王总实际付给 Totem 的款项在“向 Totem 付款”单独登记，两个方向分别记账。</li><li>使用系统库存时，点击“确认放行，准备系统出库”，由仓库按现有订单和车辆扫码办理。真实库存及数量仍校验。</li><li>历史 PI 保留原办理规则；已确认单据的模式不被页面切换自动改变。</li></ul></article>
    <article><h4>② 捷克代理当地内销</h4><p>登记客户及工厂合同（含 VAT） → 跟进备货 → 催款并确认工厂已收款 → 联系提货并记录交接 → 办理结案。</p><ul><li>不需要业务员制作 PI。客户直接向 Totem／工厂付款，不经过我方转付。</li><li>先付款后取货。收到工厂到账反馈后，点击“确认工厂已收款，继续下一步”，即可办理提货；凭证及金额明细允许后补。</li><li>VAT 金额按工厂合同填写，系统不按国家自动猜税率。</li></ul></article>
    <article><h4>③ 工厂直签外销</h4><p>登记外销合同（按合同不含 VAT） → 跟进备货 → 催款并确认工厂已收款 → 联系提货并记录交接 → 办理结案。</p><p>同样不做业务员 PI，客户直接付工厂，先付款后提货。运输、合同及外销单据的办理情况按实际约定填写。</p></article>
    <article><h4>正常填写、确认推进与后补资料</h4><ul><li>新建直签订单先选客户和币种，其余资料允许逐步填写。保存资料不会生成资金流水或扣库存。</li><li>确认记录保存经办人和时间；需要更正时使用“恢复待核对”，再补录并确认。撤回工厂收款确认后，提货确认也恢复待核对。</li><li>“工厂实际到账”仅记客户付工厂的款项。凭证号／参考号、收付方和说明可点击“补录”；错误金额使用“撤回”并重新登记，保留历史。此处记录凭证参考信息，不提供文件上传。</li><li>使用系统库存的直签业务：从库存与锁定建立普通销售订单，关联到直签订单，再沿用现有订单确认、库存占用及扫码出库入口。工厂直接交货可只记录交接情况。</li><li>系统销售订单不可同时作为 PI 和工厂直签的履约订单，避免重复归属。</li></ul></article>
    <p class="guide-tip">流程确认表示经办人已核对本环节。实际到账、Totem 付款和实际出库，以各自业务记录为准。</p>
  </section>
</template>
<style scoped>.europe-guide{color:#314a60;line-height:1.9}.europe-guide h3{margin:0 0 8px;color:#194c79}.europe-guide h4{font-size:16px;margin:0 0 8px;color:#194c79}.europe-guide article{padding:18px 0;border-bottom:1px solid #e4ebf2}.europe-guide p{margin:6px 0}.europe-guide ul{padding-left:22px;margin:6px 0}.guide-links{display:flex;gap:18px;flex-wrap:wrap;margin:12px 0}.guide-links a{color:#1767ad}.guide-tip{padding:12px;background:#eff6fc;border-radius:6px}</style>
