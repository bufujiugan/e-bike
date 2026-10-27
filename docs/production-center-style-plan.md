# 生产中心参考图调整

范围：按本轮参考图调整生产中心，保留其他页面样式及现有业务接口。

- [x] 顶部工厂场景、蓝色宣传卡及八个业务标签。
- [x] 四张指标卡和紧凑的八节点生产流程。
- [x] 当前任务表、四工位扫码进度、生产任务状态统计三栏。
- [x] 生产趋势与异常信息两栏，原完整任务列表保持可用。
- [x] 构建、1440/1680/1920浏览器检查、详情/筛选/分页回归。

数据边界：统计基于原任务查询返回的当前页，任务总数使用服务端total。日期筛选、趋势均标明当前页样本。无计划数量、完成时间、设备运行状态及异常任务关联汇总，不合成此类业务数据。趋势按创建日期统计任务数及其中当前已完成数，不冒充按完成时间统计的产量。

文件：ProductionOverview.vue负责展示与派生数据；ProductionManager.vue仅增加页面内定位事件；AppLayout.vue提供生产中心样式作用域；PageHero.vue选择生产中心场景及宣传卡；production-center.css仅影响生产中心；main.ts引入样式。无新增API，无数据库写入。

## 验收记录

- Vue/TypeScript 与 Vite 构建通过；保留既有主bundle超过500KB提示。
- 82项原业务契约检查及 `git diff --check` 通过。
- 1440×900、1680×1050、1920×1080：单一主标题、头图在Hero边界内、三栏同排、四张工位卡、两个图表、五行任务摘要，无整页横向溢出，无JS错误，默认任务页读取无API错误。
- “更多任务”定位到完整列表；筛选作业中后，五行摘要和四工位已绑定数逐一与真实API返回匹配。
- 摘要详情读取原任务详情API；统计范围、趋势7/30天选项可切换。
- 真实无匹配查询清空摘要和图表，重置恢复；原任务列表第二页读取34条并更新概览。
- 商品SKU切换后不显示任务概览，切回生产任务恢复。
- 人为中断一次任务读取后，图表清空并显示错误；解除拦截后刷新恢复两个图表。
- 异常区域按钮正确切到原异常隔离Tab且只请求一次。原 `GET /api/production/issues` 仍返回500，既有数据库排序规则问题未在样式任务中修改；未知异常汇总不会显示为“无异常”或零。
- 首页与库存管理未应用生产中心专用样式，公共Hero正常。
- 浏览器交互没有发起业务写请求。未执行造数、硬件验收或独立账户服务端权限验收。

使用 [Playwright 技能](C:/Users/DELL/.codex/skills/playwright/SKILL.md) 进行浏览器截图、布局测量和上述交互验证。

入口：<http://127.0.0.1:5173/production>。现有静态服务已加载新的 `bike-web/dist`，后端未重启。

最终首屏截图：`output/playwright/production-center-final-1680.png`。

全页截图：`output/playwright/production-reference-{1440,1680,1920}.png`。

本地验证脚本：`tmp/check-production-redesign.js`、`tmp/check-production-redesign-interactions.js`、`tmp/check-production-redesign-boundaries.js`。
