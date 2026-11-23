<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import api from '../api'
import AppIcon from './AppIcon.vue'

type Notice = {
  id: number
  kind: 'ORDER' | 'PI'
  documentId: number
  documentNo: string
  batchNo: string | null
  quantity: number
  dispatchedAt: string
  readAt: string | null
}
type NoticePage = { records: Notice[]; page: number; pageSize: number; total: number }
type Pending = { unreadCount: number; nextNotification: Notice | null }
const emit = defineEmits<{ order: [orderNo: string]; pi: [piId: number] }>()
const endpoint = '/api/dispatch-notifications'
const view = ref<'list' | 'detail' | null>(null)
const selection = ref<Notice | null>(null)
const fromList = ref(false)
const unreadCount = ref(0)
const unreadOnly = ref(false)
const notices = ref<NoticePage | null>(null)
const listLoading = ref(false)
const saving = ref(false)
const listError = ref(false)
const saveError = ref(false)
let shownThrough = 0
let pollTimer: number
let pendingRequest: AbortController | undefined
let listRequest: AbortController | undefined
let readRequest: AbortController | undefined
let disposed = false

const title = computed(() => view.value === 'list' ? '出库通知' : selection.value?.kind === 'PI' ? 'PI 整批出库成功' : '订单出库成功')
const bellLabel = computed(() => unreadCount.value ? '出库通知，' + unreadCount.value + ' 条未读' : '出库通知')
const dialogVisible = computed({
  get: () => view.value !== null,
  set: visible => { if (!visible) view.value = null },
})

async function refreshPending(allowPopup = true) {
  if (disposed || pendingRequest || saving.value || document.hidden) return
  const request = new AbortController()
  pendingRequest = request
  try {
    const { data } = await api.get<{ data: Pending }>(endpoint + '/pending', {
      params: { afterId: shownThrough }, signal: request.signal, timeout: 10000,
    })
    if (request.signal.aborted) return
    const pending = data.data
    const countChanged = unreadCount.value !== pending.unreadCount
    unreadCount.value = pending.unreadCount
    if (countChanged && view.value === 'list' && !listLoading.value) void loadList(notices.value?.page)
    if (allowPopup && !document.hidden && !view.value && pending.nextNotification) {
      shownThrough = pending.nextNotification.id
      openNotice(pending.nextNotification, false)
    }
  } catch (error) {
    if (!request.signal.aborted && !api.isAxiosError(error)) throw error
    // 等待下一次轮询，列表始终可以手动重新加载。
  } finally {
    if (pendingRequest === request) pendingRequest = undefined
  }
}

async function loadList(page = 1) {
  listRequest?.abort()
  const request = new AbortController()
  listRequest = request
  listLoading.value = true
  listError.value = false
  try {
    const { data } = await api.get<{ data: NoticePage }>(endpoint, {
      params: { page, unreadOnly: unreadOnly.value }, signal: request.signal, timeout: 10000,
    })
    if (!request.signal.aborted) notices.value = data.data
  } catch {
    if (!request.signal.aborted) listError.value = true
  } finally {
    if (listRequest === request) {
      listRequest = undefined
      listLoading.value = false
    }
  }
}

function showList() {
  view.value = 'list'
  void loadList(notices.value?.page)
  void refreshPending(false)
}

function filterList(onlyUnread: boolean) {
  unreadOnly.value = onlyUnread
  notices.value = null
  void loadList()
}

function openNotice(notice: Notice, openedFromList: boolean) {
  selection.value = notice
  fromList.value = openedFromList
  saveError.value = false
  view.value = 'detail'
}

async function acknowledge(openDocument: boolean) {
  const notice = selection.value
  if (!notice || saving.value) return
  if (!notice.readAt) {
    const request = new AbortController()
    readRequest = request
    pendingRequest?.abort()
    pendingRequest = undefined
    saving.value = true
    saveError.value = false
    try {
      await api.post(endpoint + '/' + notice.id + '/read', null, { signal: request.signal, timeout: 10000 })
    } catch {
      if (!request.signal.aborted) saveError.value = true
      return
    } finally {
      readRequest = undefined
      saving.value = false
    }
    if (request.signal.aborted) return
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }
  if (openDocument) {
    view.value = null
    if (notice.kind === 'PI') emit('pi', notice.documentId)
    else emit('order', notice.documentNo)
  } else if (fromList.value) showList()
  else view.value = null
  void refreshPending(false)
}

function displayTime(value: string) {
  return value.replace('T', ' ').slice(0, 19)
}

function checkForNotices() {
  void refreshPending()
}

onMounted(() => {
  checkForNotices()
  pollTimer = window.setInterval(checkForNotices, 15000)
  window.addEventListener('focus', checkForNotices)
  document.addEventListener('visibilitychange', checkForNotices)
})

onUnmounted(() => {
  disposed = true
  window.clearInterval(pollTimer)
  pendingRequest?.abort()
  listRequest?.abort()
  readRequest?.abort()
  window.removeEventListener('focus', checkForNotices)
  document.removeEventListener('visibilitychange', checkForNotices)
})
</script>

<template>
  <button type="button" class="dispatch-bell" :aria-label="bellLabel" title="出库通知" aria-haspopup="dialog" @click="showList">
    <AppIcon name="bell" />
    <span v-if="unreadCount" class="dispatch-count" aria-hidden="true">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
  </button>

  <el-dialog
    v-model="dialogVisible" :title="title" width="min(550px, 94vw)" append-to-body
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    destroy-on-close
  >
    <template #header="{ titleId, titleClass }">
      <div class="dispatch-heading">
        <span class="dispatch-heading-icon"><AppIcon :name="view === 'list' ? 'bell' : 'truck'" /></span>
        <div>
          <h2 :id="titleId" :class="titleClass">{{ title }}</h2>
          <span v-if="view === 'detail' && selection" class="dispatch-meta">
            {{ displayTime(selection.dispatchedAt) }} · {{ selection.readAt ? '已读' : '未读' }}
          </span>
        </div>
      </div>
    </template>

    <template v-if="view === 'list'">
      <div class="dispatch-list-toolbar">
        <el-button-group aria-label="通知筛选">
          <el-button :type="unreadOnly ? 'default' : 'primary'" :aria-pressed="!unreadOnly" @click="filterList(false)">全部</el-button>
          <el-button :type="unreadOnly ? 'primary' : 'default'" :aria-pressed="unreadOnly" @click="filterList(true)">未读</el-button>
        </el-button-group>
        <span class="dispatch-meta">{{ unreadCount }} 条未读</span>
      </div>
      <div v-loading="listLoading" class="dispatch-list">
        <div v-if="listError" class="dispatch-list-error" role="status">
          <span>通知列表加载失败</span><el-button link type="primary" @click="loadList(notices?.page)">重试</el-button>
        </div>
        <ul v-else-if="notices?.records.length" class="dispatch-rows">
          <li v-for="notice in notices.records" :key="notice.id">
            <button type="button" class="dispatch-row" @click="openNotice(notice, true)">
              <span>
                <strong>{{ notice.kind === 'PI' ? 'PI 整批出库' : '订单出库' }} · {{ notice.documentNo }}</strong>
                <span class="dispatch-meta">{{ displayTime(notice.dispatchedAt) }} · {{ notice.quantity }} 台</span>
              </span>
              <span :class="['dispatch-state', { 'dispatch-state-unread': !notice.readAt }]">{{ notice.readAt ? '已读' : '未读' }}</span>
            </button>
          </li>
        </ul>
        <el-empty v-else-if="!listLoading" :description="unreadOnly ? '暂无未读出库通知' : '暂无出库通知'" :image-size="64" />
      </div>
      <el-pagination
        v-if="!listError && notices && notices.total > notices.pageSize"
        :current-page="notices.page" :page-size="notices.pageSize" :total="notices.total"
        layout="prev, pager, next" size="small" :pager-count="5" :disabled="listLoading" @current-change="loadList"
      />
    </template>

    <template v-else-if="view === 'detail' && selection">
      <dl class="dispatch-details">
        <dt>{{ selection.kind === 'PI' ? 'PI 编号' : '订单号' }}</dt><dd>{{ selection.documentNo }}</dd>
        <template v-if="selection.batchNo"><dt>出库批次号</dt><dd>{{ selection.batchNo }}</dd></template>
        <dt>本次出库</dt><dd>{{ selection.quantity }} 台</dd>
      </dl>
      <el-alert v-if="saveError" title="已读状态保存失败，请重试" type="warning" :closable="false" />
    </template>

    <template v-if="view === 'detail' && selection" #footer>
      <el-button :disabled="saving" @click="acknowledge(false)">
        {{ selection.readAt ? '返回列表' : '我知道了' }}
      </el-button>
      <el-button type="primary" :disabled="saving" @click="acknowledge(true)">
        {{ saving ? '正在保存…' : '查看详情' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dispatch-bell { position: relative; display: grid; flex: none; place-items: center; width: 2.75rem; height: 2.75rem; padding: 0; border: 0; border-radius: .6rem; background: transparent; color: #375472; cursor: pointer; }
.dispatch-bell .app-icon { width: 1.25rem; height: 1.25rem; }
.dispatch-bell:hover { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.dispatch-bell:focus-visible, .dispatch-row:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.dispatch-count { position: absolute; top: .1rem; right: 0; min-width: 1rem; padding: 0 .22rem; border: 1px solid #fff; border-radius: 2rem; background: #b42332; color: #fff; font-size: .65rem; line-height: 1rem; text-align: center; }
.dispatch-heading { display: flex; align-items: center; gap: .8rem; padding-right: 1.75rem; }
.dispatch-heading h2 { margin: 0; font-size: 1.15rem; font-weight: 650; }
.dispatch-heading-icon { display: grid; flex: none; place-items: center; width: 2.65rem; height: 2.65rem; border-radius: .75rem; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.dispatch-heading-icon .app-icon { width: 1.3rem; height: 1.3rem; }
.dispatch-meta { display: block; margin-top: .25rem; color: var(--el-text-color-regular); font-size: .8125rem; }
.dispatch-list-toolbar, .dispatch-row, .dispatch-list-error { display: flex; align-items: center; justify-content: space-between; gap: .75rem; }
.dispatch-list { min-height: 5rem; max-height: 52vh; margin: .7rem 0; overflow-y: auto; }
.dispatch-list-error { justify-content: center; min-height: 5rem; }
.dispatch-rows { margin: 0; padding: 0; list-style: none; }
.dispatch-row { width: 100%; padding: .7rem .55rem; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); border-radius: .2rem; background: transparent; color: var(--el-text-color-primary); font: inherit; text-align: left; overflow-wrap: anywhere; cursor: pointer; }
.dispatch-row:hover { background: var(--el-fill-color-light); }
.dispatch-state { flex: none; color: var(--el-text-color-regular); font-size: .8rem; }
.dispatch-state-unread { font-weight: 700; }
.dispatch-details { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: .85rem 1.4rem; margin: 0 0 .75rem; padding: 1rem; border: 1px solid var(--el-border-color-lighter); border-radius: .75rem; background: var(--el-fill-color-light); }
.dispatch-details dt { color: var(--el-text-color-regular); }
.dispatch-details dd { margin: 0; color: var(--el-text-color-primary); font-weight: 600; overflow-wrap: anywhere; }
</style>
