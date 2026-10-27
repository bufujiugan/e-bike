<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { fulfillmentSequence, sequenceRoles, type SequenceMessage, type SequenceRole } from './productionFulfillmentSequence'

const emit = defineEmits<{ select: [index: number] }>()
const phaseIndex = ref(0)
const selectedIndex = ref(0)
const chart = ref<HTMLElement>()
const inspector = ref<HTMLElement>()
const showExceptions = ref(true)
const phase = computed(() => fulfillmentSequence[phaseIndex.value]!)
const messages = computed(() => phase.value.groups.flatMap(group => group.messages))
const selected = computed(() => messages.value[selectedIndex.value]!)
const chartWidth = computed(() => phase.value.roles.length * 188 + 32)
const laneX = (role: SequenceRole) => 110 + phase.value.roles.indexOf(role) * 188
const kinds = { action: '岗位操作', result: '系统反馈', condition: '条件 / 异常分支', handoff: '岗位交接' }
const colors = { action: '#286bd5', result: '#18836c', condition: '#b87518', handoff: '#7660b5' }
const fills = { action: '#f0f6ff', result: '#edf9f4', condition: '#fff7e9', handoff: '#f5f1fc' }
const roleSummary = computed(() => [...new Set(phase.value.roles.filter(role => role !== 'system').map(role => sequenceRoles[role].name))].join(' / '))
type MessageRow = { message: SequenceMessage; index: number; y: number }
const layout = computed(() => {
  const rows: MessageRow[] = [], groups: { title: string; tag: string; y: number; count: number }[] = []
  let y = 16, index = 0
  for (const group of phase.value.groups) {
    const visible = group.messages.filter(item => showExceptions.value || item.kind !== 'condition')
    if (visible.length) { groups.push({ title: group.title, tag: group.tag, y, count: visible.length }); y += 58 }
    for (const message of group.messages) {
      if (showExceptions.value || message.kind !== 'condition') { rows.push({ message, index, y }); y += 126 }
      index++
    }
    if (visible.length) y += 14
  }
  return { rows, groups, height: y + 8 }
})
const wrap = (text: string, length: number) => Array.from(text).reduce<string[]>((lines, char, index) => {
  const line = Math.floor(index / length)
  lines[line] = (lines[line] ?? '') + char
  return lines
}, [])
const centerX = (message: SequenceMessage) => (laneX(message.from) + laneX(message.to)) / 2
const rowLabel = (row: MessageRow) => `${row.index + 1}. ${row.message.title}，${sequenceRoles[row.message.from].name}到${sequenceRoles[row.message.to].name}`
async function selectMessage(index: number, scroll = false) {
  selectedIndex.value = index
  if (!scroll && window.matchMedia('(max-width:1050px)').matches) {
    await nextTick()
    inspector.value?.scrollIntoView({ block: 'start', behavior: 'auto' })
  }
  if (scroll) {
    if (messages.value[index]?.kind === 'condition') showExceptions.value = true
    await nextTick()
    const element = chart.value?.querySelector<SVGGElement>(`[data-message-index="${index}"]`)
    if (element && chart.value) {
      const offset = element.getBoundingClientRect().top - chart.value.getBoundingClientRect().top
      chart.value.scrollTop += offset - 105
    }
  }
}
function changePhase(index: number) {
  phaseIndex.value = index
  selectedIndex.value = 0
  if (chart.value) { chart.value.scrollTop = 0; chart.value.scrollLeft = 0 }
}
function toggleExceptions() {
  showExceptions.value = !showExceptions.value
  if (!showExceptions.value && selected.value.kind === 'condition') {
    selectedIndex.value = Math.max(0, messages.value.findIndex(item => item.kind !== 'condition'))
  }
}
</script>

<template>
  <section class="role-sequence">
    <header class="sequence-heading"><div><span class="sequence-eyebrow">岗位协作 · 从准备到交付</span><h3>看清每一步，知道何时接手</h3><p>按阶段查看操作与反馈。点击任一箭头步骤，右侧会显示输入、校验、结果和异常处理。</p></div><span class="sequence-count">4 个阶段 · {{ fulfillmentSequence.reduce((total, item) => total + item.groups.reduce((sum, group) => sum + group.messages.length, 0), 0) }} 个协作步骤</span></header>
    <nav class="phase-tabs" aria-label="时序图阶段">
      <button v-for="(item, index) in fulfillmentSequence" :key="item.title" type="button" :class="{ active: phaseIndex === index }" :aria-pressed="phaseIndex === index" @click="changePhase(index)"><span class="phase-order">0{{ index + 1 }}</span><span><strong>{{ item.title }}</strong><small>{{ item.subtitle }}</small></span></button>
    </nav>
    <div class="phase-context"><article><span>什么时候开始</span><p>{{ phase.before }}</p></article><article><span>完成后交出什么</span><p>{{ phase.deliver }}</p></article><article><span>这一阶段特别注意</span><p>{{ phase.decision }}</p></article></div>
    <div class="sequence-toolbar"><div class="sequence-legend"><span v-for="(name, kind) in kinds" :key="kind"><i :style="{ background: colors[kind] }" />{{ name }}</span></div><button type="button" class="exception-toggle" :aria-pressed="showExceptions" @click="toggleExceptions">{{ showExceptions ? '✓ 已显示条件与异常分支' : '显示条件与异常分支' }}</button></div>
    <div class="sequence-workspace">
      <div class="chart-panel">
        <div class="chart-caption"><strong>{{ phase.title }} · {{ messages.length }} 步</strong><span>向下阅读；窄屏可左右滑动</span></div>
        <div ref="chart" class="sequence-chart" tabindex="0" role="region" :aria-label="`${phase.title}角色时序图，可滚动`">
          <div class="chart-surface" :style="{ minWidth: `${chartWidth}px` }">
            <div class="lane-headings" :style="{ gridTemplateColumns: `repeat(${phase.roles.length}, 1fr)` }"><div v-for="role in phase.roles" :key="role" :class="{ 'system-lane': role === 'system' }"><strong>{{ sequenceRoles[role].name }}</strong><small>{{ sequenceRoles[role].location }}</small></div></div>
            <svg class="sequence-canvas" :viewBox="`0 0 ${chartWidth} ${layout.height}`" :width="chartWidth" :height="layout.height" role="group" :aria-label="`${phase.title}操作与交接顺序`">
              <title>{{ phase.title }}详细角色时序图</title>
              <g aria-hidden="true"><line v-for="role in phase.roles" :key="role" :x1="laneX(role)" y1="0" :x2="laneX(role)" :y2="layout.height" stroke="#d5e0ef" stroke-width="1.5" stroke-dasharray="4 5" /></g>
              <g v-for="(group, index) in layout.groups" :key="group.title" aria-hidden="true"><rect x="12" :y="group.y" :width="chartWidth - 24" height="42" rx="6" fill="#edf2f8"/><text x="26" :y="group.y + 26" class="group-title">{{ index + 1 }} / {{ group.title }}</text><text :x="chartWidth - 28" :y="group.y + 26" text-anchor="end" class="group-tag">{{ group.tag }}</text></g>
              <g v-for="row in layout.rows" :key="row.index" :data-message-index="row.index" class="message-row" :class="{ selected: selectedIndex === row.index }" tabindex="0" role="button" :aria-label="rowLabel(row)" :aria-pressed="selectedIndex === row.index" @click="selectMessage(row.index)" @keydown.enter.prevent="selectMessage(row.index)" @keydown.space.prevent="selectMessage(row.index)">
                <rect class="row-focus" x="8" :y="row.y" :width="chartWidth - 16" height="116" rx="8" :fill="selectedIndex === row.index ? '#f3f7fd' : 'transparent'" :stroke="selectedIndex === row.index ? '#9ebfe8' : 'transparent'" />
                <rect :x="centerX(row.message) - 164" :y="row.y + 4" width="328" height="75" rx="8" :fill="fills[row.message.kind]" :stroke="selectedIndex === row.index ? colors[row.message.kind] : '#dfe8f3'" />
                <text v-for="(line, index) in wrap(row.message.title, 22)" :key="`title-${index}`" :x="centerX(row.message)" :y="row.y + 25 + index * 17" text-anchor="middle" class="message-title">{{ line }}</text>
                <text v-for="(line, index) in wrap(row.message.summary, 26)" :key="`summary-${index}`" :x="centerX(row.message)" :y="row.y + 27 + wrap(row.message.title, 22).length * 17 + index * 15" text-anchor="middle" class="message-subtitle">{{ line }}</text>
                <line :x1="laneX(row.message.from)" :y1="row.y + 96" :x2="laneX(row.message.to)" :y2="row.y + 96" :stroke="colors[row.message.kind]" stroke-width="2" :stroke-dasharray="row.message.kind === 'result' ? '6 4' : undefined" />
                <path :d="`M ${laneX(row.message.to) + (laneX(row.message.to) > laneX(row.message.from) ? -9 : 9)} ${row.y + 90} L ${laneX(row.message.to)} ${row.y + 96} L ${laneX(row.message.to) + (laneX(row.message.to) > laneX(row.message.from) ? -9 : 9)} ${row.y + 102}`" fill="none" :stroke="colors[row.message.kind]" stroke-width="2" />
                <circle :cx="laneX(row.message.from)" :cy="row.y + 96" r="11" :fill="colors[row.message.kind]"/><text :x="laneX(row.message.from)" :y="row.y + 100" text-anchor="middle" class="message-number">{{ row.index + 1 }}</text>
              </g>
            </svg>
          </div>
        </div>
        <div class="chart-footer"><span>实线：操作或交接　虚线：系统反馈</span><span>橙色步骤仅在对应条件发生时执行</span></div>
      </div>
      <aside ref="inspector" class="step-inspector" aria-label="所选步骤详情" aria-live="polite">
        <div class="inspector-top"><span>步骤 {{ String(selectedIndex + 1).padStart(2, '0') }} / {{ messages.length }}</span><span :style="{ color: colors[selected.kind] }">{{ kinds[selected.kind] }}</span></div>
        <h4>{{ selected.title }}</h4><p class="inspector-route">{{ sequenceRoles[selected.from].name }} <span>→</span> {{ sequenceRoles[selected.to].name }}</p>
        <dl><div><dt>在哪里做</dt><dd>{{ selected.location }}</dd></div><div><dt>填写 / 核对什么</dt><dd>{{ selected.input }}</dd></div><div><dt>系统校验 / 业务规则</dt><dd>{{ selected.check }}</dd></div><div class="result-detail"><dt>成功后得到什么</dt><dd>{{ selected.result }}</dd></div><div class="recovery-detail"><dt>不成功时怎么办</dt><dd>{{ selected.recovery }}</dd></div></dl>
        <button type="button" class="detail-entry" @click="emit('select', selected.guideStep)">查看完整操作与页面入口 ↗</button>
        <div class="step-pagination"><button type="button" :disabled="selectedIndex === 0" @click="selectMessage(selectedIndex - 1, true)">← 上一步</button><button type="button" :disabled="selectedIndex >= messages.length - 1" @click="selectMessage(selectedIndex + 1, true)">下一步 →</button></div>
      </aside>
    </div>
    <div class="phase-handoff"><span>阶段交接</span><p><strong>{{ roleSummary }}</strong><br>{{ phase.deliver }}</p><button v-if="phaseIndex < fulfillmentSequence.length - 1" type="button" @click="changePhase(phaseIndex + 1)">下一阶段：{{ fulfillmentSequence[phaseIndex + 1]?.title }} →</button><span v-else class="handoff-finished">完成后按订单核对实际交付</span></div>
    <details class="sequence-readable"><summary>展开本阶段全部文字步骤（{{ messages.length }} 步）</summary><ol><li v-for="(message, index) in messages" :key="index"><button type="button" @click="selectMessage(index, true)">{{ index + 1 }}. {{ message.title }}</button><p>{{ sequenceRoles[message.from].name }} → {{ sequenceRoles[message.to].name }} · {{ kinds[message.kind] }}</p><p>{{ message.location }}；{{ message.input }}</p><p><strong>结果：</strong>{{ message.result }}</p></li></ol></details>
  </section>
</template>

<style scoped>
.role-sequence{color:#314c68;margin-top:20px;min-width:0}.sequence-heading{display:flex;justify-content:space-between;gap:20px;align-items:flex-start}.sequence-eyebrow{color:#507ca6;font-size:11px;letter-spacing:1px}.sequence-heading h3{font-size:21px;color:#173f66;margin:6px 0 8px}.sequence-heading p{font-size:13px;line-height:1.8;margin:0;color:#6b7d91}.sequence-count{white-space:nowrap;font-size:12px;padding:7px 10px;background:#eaf3ff;color:#2a6eba;border-radius:20px}.phase-tabs{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:20px 0 14px}.phase-tabs button{font:inherit;display:flex;gap:10px;align-items:center;text-align:left;padding:13px 12px;cursor:pointer;border:1px solid #dae4f1;background:white;color:#365673;border-radius:9px}.phase-order{font-size:20px;color:#90a9c2;font-weight:700}.phase-tabs strong{display:block;font-size:14px}.phase-tabs small{display:block;font-size:11px;color:#7d8fa3;margin-top:5px;line-height:1.5}.phase-tabs button.active{border-color:#2374d6;background:#edf5ff;box-shadow:inset 0 3px #2374d6}.phase-tabs .active .phase-order,.phase-tabs .active strong{color:#1765bc}.phase-context{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border:1px solid #e1e9f2;border-radius:9px;background:#fafcfe}.phase-context article{padding:13px 15px}.phase-context article+article{border-left:1px solid #e1e9f2}.phase-context article>span{font-size:11px;font-weight:600;color:#6482a0}.phase-context p{font-size:12px;line-height:1.8;margin:6px 0 0}.sequence-toolbar{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap;margin:16px 0 12px}.sequence-legend{display:flex;flex-wrap:wrap;gap:12px;font-size:11px;color:#657d94}.sequence-legend span{display:flex;align-items:center;gap:5px}.sequence-legend i{width:7px;height:7px;border-radius:50%}.exception-toggle{font:inherit;font-size:12px;cursor:pointer;color:#9b6419;background:#fffaf0;border:1px solid #eddcbe;border-radius:6px;padding:7px 9px}.sequence-workspace{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:14px;align-items:start}.chart-panel{min-width:0;border:1px solid #dbe5f0;border-radius:10px;overflow:hidden;background:white}.chart-caption{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:13px 15px;background:#f5f8fd;font-size:13px}.chart-caption span{font-size:11px;color:#7d91a7}.sequence-chart{height:620px;max-height:72vh;overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.chart-surface{width:100%}.lane-headings{position:sticky;top:0;z-index:1;display:grid;gap:10px;padding:12px 16px;background:#fffefc;border-bottom:1px solid #edf1f6}.lane-headings>div{border:1px solid #d9e5f6;background:#f1f6ff;border-radius:7px;text-align:center;padding:10px 4px}.lane-headings strong{font-size:13px;display:block}.lane-headings small{font-size:10px;display:block;margin-top:5px;color:#7890aa}.lane-headings .system-lane{background:#eef8f2;border-color:#cbe7d6;color:#2b6f54}.sequence-canvas{display:block;width:100%;height:auto;font-family:inherit}.group-title{font-size:12px;font-weight:600;fill:#4b6683}.group-tag{font-size:10px;fill:#8498ad}.message-row{cursor:pointer;outline:none}.message-row:hover .row-focus{stroke:#c5d8ef;fill:#f8fbff}.message-row:focus-visible .row-focus{stroke:#176bd0;stroke-width:2}.message-title{font-size:12px;font-weight:600;fill:#294d75}.message-subtitle{font-size:10.5px;fill:#6f849a}.message-number{font-size:10px;fill:white;font-weight:700}.chart-footer{display:flex;gap:8px;justify-content:space-between;flex-wrap:wrap;padding:10px 13px;border-top:1px solid #e5edf5;font-size:10px;color:#7f91a4}.step-inspector{border:1px solid #cddff1;border-radius:10px;padding:16px;background:#fcfdff;min-width:0}.inspector-top{display:flex;justify-content:space-between;font-size:11px;color:#7f95ac;gap:6px}.step-inspector h4{font-size:17px;line-height:1.5;color:#19466e;margin:12px 0 7px}.inspector-route{font-size:12px;color:#557795;line-height:1.7;margin:0 0 15px}.inspector-route span{margin:0 6px;color:#90a7bf}.step-inspector dl{margin:0}.step-inspector dl>div{padding:10px 0;border-top:1px solid #e4ecf4}.step-inspector dt{font-size:11px;color:#8294a9;margin-bottom:5px}.step-inspector dd{margin:0;font-size:12px;line-height:1.8;color:#405f7d;overflow-wrap:anywhere}.step-inspector .result-detail{padding:10px;background:#eff9f3;border-radius:6px;border:0;margin:7px 0}.result-detail dt{color:#42886a}.step-inspector .recovery-detail{padding:10px;background:#fff7e9;border-radius:6px;border:0}.recovery-detail dt{color:#a17532}.detail-entry{font:inherit;display:block;width:100%;cursor:pointer;border:1px solid #c9dff8;border-radius:6px;background:#eef5ff;color:#226cc3;padding:10px 6px;font-size:12px;margin-top:14px}.step-pagination{display:flex;justify-content:space-between;margin-top:12px}.step-pagination button{font:inherit;font-size:12px;border:0;background:none;color:#3d76b9;cursor:pointer;padding:4px}.step-pagination button:disabled{color:#b3becb;cursor:default}.phase-handoff{display:flex;gap:14px;align-items:center;flex-wrap:wrap;background:#f1f8f6;border:1px solid #d9eae3;border-radius:9px;padding:14px 16px;margin:16px 0}.phase-handoff>span:first-child{font-size:12px;color:#3a7c68;font-weight:600}.phase-handoff p{flex:1;font-size:12px;line-height:1.8;margin:0;color:#53796c}.phase-handoff strong{font-weight:500}.phase-handoff button{font:inherit;font-size:12px;border:1px solid #c6dfd4;background:white;border-radius:6px;color:#2c7b5c;padding:9px 12px;cursor:pointer}.handoff-finished{font-size:12px;color:#4e806d}.sequence-readable{font-size:12px;line-height:1.8;color:#687f95}.sequence-readable summary{cursor:pointer;color:#2d70b7;padding:8px 0}.sequence-readable ol{padding-left:0;list-style:none;display:grid;gap:10px}.sequence-readable li{padding:12px;border:1px solid #e1e9f4;border-radius:8px}.sequence-readable button{font:inherit;text-align:left;font-weight:600;color:#2668a8;border:0;background:none;cursor:pointer;padding:0}.sequence-readable p{margin:5px 0 0}@media(max-width:1050px){.sequence-workspace{grid-template-columns:minmax(0,1fr)}.step-inspector dl{display:grid;grid-template-columns:1fr 1fr;gap:10px}.step-inspector .result-detail{margin:0}.sequence-chart{height:520px}.step-inspector{border-top:3px solid #6ca2e5}.sequence-heading{flex-wrap:wrap}}@media(max-width:600px){.phase-tabs{grid-template-columns:1fr 1fr;gap:8px}.phase-tabs button{padding:10px 8px;gap:7px}.phase-tabs small{font-size:10px}.phase-order{font-size:17px}.phase-context{grid-template-columns:1fr}.phase-context article{padding:10px 12px}.phase-context article+article{border-left:0;border-top:1px solid #e1e9f2}.sequence-heading h3{font-size:18px}.sequence-heading{gap:10px}.step-inspector dl{grid-template-columns:1fr}.sequence-chart{height:430px;max-height:65vh}.chart-caption{flex-wrap:wrap}.phase-handoff p{flex-basis:100%}.phase-handoff button{width:100%}.sequence-count{font-size:11px}.sequence-toolbar{gap:10px}}
</style>
