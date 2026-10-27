<script setup lang="ts">
import ProductionRoleSequence from './ProductionRoleSequence.vue'

defineProps<{ mode: 'flow' | 'sequence' }>()
const emit = defineEmits<{ select: [index: number] }>()
type FlowNode = { step: number; title: string; owner: string; detail: string; tag: string; automatic?: boolean; conditional?: boolean }
const phases: { title: string; timing: string; outcome: string; nodes: FlowNode[] }[] = [
  { title: '生产前 · 把资料备齐', timing: '首次生产或更换规格时', outcome: '交接：可用 SKU、部件 SN 和已下达工单', nodes: [
    { step: 0, title: '确认车型与标签', owner: '计划员 · Web', detail: '启用车型，准备可用标签模板', tag: '准备资料' },
    { step: 2, title: '建立商品 SKU', owner: '计划员 · 商品准备', detail: '一套确定配置对应一个 SKU', tag: '定义规格' },
    { step: 3, title: '登记三个部件', owner: '来料人员 · 零部件管理', detail: '电机 / 电池 / 仪表，确认质检合格', tag: '按实物登记' },
    { step: 4, title: '下达生产工单', owner: '计划员 · 生产办理', detail: '选择 SKU，填写批次和计划数量', tag: '安排生产' },
  ] },
  { title: '生产中 · 每台车扫码入库', timing: '工单已下达、部件可用后', outcome: '交接：已入库车辆与可用 SKU 库存', nodes: [
    { step: 5, title: '选工单，扫四码', owner: '组装员 · PDA 组装', detail: '车架 / 电机 / 电池 / 仪表，顺序不限', tag: '每台车重复' },
    { step: 5, title: '绑定并生成总码', owner: '系统自动', detail: '第四码完成后生成总码和打印任务', tag: '自动处理', automatic: true },
    { step: 6, title: '打印并核对实物', owner: '打印员 · PDA / 打印队列', detail: '核对标签内容、实际出纸并贴到车辆', tag: '人工确认' },
    { step: 7, title: '扫总码，确认入库', owner: '仓管员 · PDA 入库', detail: '选择实际仓库、库位后确认', tag: '形成库存' },
  ] },
  { title: '接单时 · 按数量为客户留货', timing: '已有合格库存、客户确定需求后', outcome: '交接：销售订单、客户与待发数量', nodes: [
    { step: 8, title: '核对可新增锁定量', owner: '业务员 · 库存与锁定', detail: '扣除隔离、临时锁定和订单占用', tag: '先查余量' },
    { step: 8, title: '为客户锁定数量', owner: '业务员 · 库存与锁定', detail: '填写数量，选择客户 / 代理商及有效期', tag: '留货' },
    { step: 9, title: '锁定转销售订单', owner: '业务员 · 锁定转订单', detail: '核对 SKU、客户、数量及计划出库日', tag: '保留占用' },
    { step: 10, title: '确认销售订单', owner: '订单员 · 订单与出库', detail: '确认后进入待出库状态', tag: '准备交仓库' },
  ] },
  { title: '发货前后 · 放行、出库与核对', timing: '订单待出库或部分出库且仍有余量', outcome: '完成：出库记录可追溯到订单、总码和车架号', nodes: [
    { step: 10, title: '判断是否关联 PI', owner: '订单员 · 订单办理', detail: '关联 PI：确认、到账登记并完成放行', tag: '有 PI 先放行', conditional: true },
    { step: 11, title: '选客户与订单', owner: '仓管员 · PDA 出库', detail: '无 PI 或 PI 已满足放行条件后办理', tag: '进入出库' },
    { step: 11, title: '扫码并确认批量出库', owner: '仓管员 · PDA 出库', detail: '逐台扫总码，核对本次数量后确认', tag: '选定实际车辆' },
    { step: 11, title: '核对出库结果', owner: '业务员 / 仓管员 · 订单办理', detail: '部分出库可继续发货；全部发完则完成', tag: '闭环核对' },
  ] },
]
</script>

<template>
  <div class="fulfillment-diagrams">
    <template v-if="mode === 'flow'">
      <div class="diagram-caption"><div><h3>先准备 → 再生产入库 → 锁数量 → 按订单出库</h3><p>从上往下看，每行从左到右。点击节点查看具体操作和入口。</p></div><span>蓝色：人工操作 · 绿色：系统自动 · 橙色：条件判断</span></div>
      <section v-for="(phase, index) in phases" :key="phase.title" class="flow-phase">
        <header><span class="phase-number">{{ index + 1 }}</span><div><h4>{{ phase.title }}</h4><p>{{ phase.timing }}</p></div></header>
        <ol class="flow-nodes">
          <li v-for="node in phase.nodes" :key="node.title">
            <button type="button" class="flow-node" :class="{ automatic: node.automatic, conditional: node.conditional }" @click="emit('select', node.step)">
              <span class="node-tag">{{ node.tag }}</span><strong>{{ node.title }}</strong><span class="node-owner">{{ node.owner }}</span><span class="node-detail">{{ node.detail }}</span><span class="node-link">查看怎么做 ↗</span>
            </button>
          </li>
        </ol>
        <p class="phase-outcome">{{ phase.outcome }}</p>
        <div v-if="index < phases.length - 1" class="phase-connector" aria-hidden="true">↓</div>
      </section>
      <div class="pi-branch"><strong>PI 分支</strong><span>确认订单 → 未关联 PI → PDA 按订单出库</span><span>确认订单 → 已关联 PI → 完成 PI 发货放行 → PDA 按订单出库</span></div>
    </template>
    <ProductionRoleSequence v-else @select="emit('select', $event)" />
    <div class="diagram-rules">
      <article><strong>SKU ≠ 单台车</strong><p>SKU 是一种确定配置；车架号标识具体车辆。新车架在 PDA 选择“车架”，录入实物号码，后台没有单独维护车架号的页面。</p></article>
      <article><strong>计划 100 台 ≠ 库存 100 台</strong><p>完成组装后还要确认入库。锁定上限按实际合格库存、已有占用及车型余量计算。</p></article>
      <article><strong>锁 30 台 ≠ 锁住整个 SKU</strong><p>假设可用库存 100 台且无其他限制：A 锁 30 台后，B 仍可锁 30 台，剩余 40 台。</p></article>
    </div>
  </div>
</template>

<style scoped>
.fulfillment-diagrams{color:#324d68}.diagram-caption{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin:18px 0}.diagram-caption h3{margin:0;font-size:17px;color:#163d63}.diagram-caption p{margin:7px 0 0;font-size:13px;line-height:1.7}.diagram-caption>span{font-size:12px;color:#6d8197;line-height:1.7;max-width:260px}.flow-phase{position:relative;margin:0 0 30px;padding:16px;background:#f8fafd;border:1px solid #e0e8f2;border-radius:12px}.flow-phase header{display:flex;gap:10px;align-items:center;margin-bottom:14px}.phase-number{width:32px;height:32px;display:grid;place-items:center;border-radius:9px;background:#176bd0;color:white;font-weight:700}.flow-phase h4{margin:0;font-size:15px}.flow-phase header p{font-size:12px;color:#75869a;margin:4px 0 0}.flow-nodes{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:26px;list-style:none;padding:0;margin:0}.flow-nodes li{position:relative;min-width:0}.flow-nodes li:not(:last-child)::after{content:'→';position:absolute;right:-21px;top:47%;color:#8aa8c7;font-size:20px}.flow-node{display:flex;flex-direction:column;align-items:flex-start;text-align:left;gap:9px;width:100%;height:100%;padding:15px;background:white;border:1px solid #bed6f1;border-radius:9px;font:inherit;cursor:pointer;color:inherit}.flow-node:hover,.flow-node:focus-visible{border-color:#1677ff;box-shadow:0 3px 12px #2169ad16;outline:2px solid #9fc7fa;outline-offset:2px}.flow-node strong{font-size:14px;color:#183e67}.node-tag{font-size:11px;color:#226cc1;background:#eef5ff;padding:3px 7px;border-radius:4px}.node-owner{font-size:12px;font-weight:600;line-height:1.6}.node-detail{font-size:12px;color:#718399;line-height:1.7}.node-link{font-size:11px;color:#2574cf;margin-top:auto;padding-top:4px}.flow-node.automatic{background:#f4fcf8;border-color:#b6ddca}.automatic .node-tag{background:#e3f5eb;color:#207755}.flow-node.conditional{background:#fffcf6;border-color:#ead3a6}.conditional .node-tag{background:#fff0d5;color:#976222}.phase-outcome{margin:13px 0 0;font-size:12px;color:#57718b;line-height:1.7}.phase-connector{position:absolute;bottom:-28px;left:50%;font-size:24px;color:#7b9dbf}.pi-branch{display:grid;gap:8px;padding:14px 18px;background:#fff8eb;border:1px solid #eed7b0;border-radius:9px;font-size:13px;line-height:1.7}.diagram-rules{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:18px 0}.diagram-rules article{padding:14px;border:1px solid #e1e9f3;border-radius:9px;background:#fafcff}.diagram-rules strong{font-size:13px;color:#1d507f}.diagram-rules p{font-size:12px;color:#6a7d90;line-height:1.8;margin:8px 0 0}@media(max-width:800px){.diagram-caption{flex-direction:column}.diagram-caption>span{max-width:none}.flow-nodes{grid-template-columns:minmax(0,1fr);gap:28px}.flow-nodes li:not(:last-child)::after{content:'↓';right:auto;left:50%;top:auto;bottom:-27px}.flow-node{gap:6px}.diagram-rules{grid-template-columns:1fr}.flow-phase{padding:12px}.diagram-caption h3{font-size:15px}}
</style>
