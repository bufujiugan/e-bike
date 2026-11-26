<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { freightForwarderApi, type FreightForwarder } from '../freight-forwarder-api'
import { tr } from '../i18n/language'

const requiredFields = [
  { key: 'name', label: '货代名称', max: 128 },
  { key: 'contactName', label: '联系人', max: 128 },
  { key: 'phone', label: '联系电话', max: 64 },
  { key: 'address', label: '地址', max: 1000 },
] as const

const forwarders = ref<FreightForwarder[]>([])
const keyword = ref('')
const loading = ref(false)
const listError = ref('')
const editorVisible = ref(false)
const editing = ref<FreightForwarder>()
const saving = ref(false)
const saveError = ref('')
const submitted = ref(false)
const form = reactive({ name: '', contactName: '', phone: '', address: '', remark: '' })

const filteredForwarders = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase()
  return forwarders.value.filter(forwarder =>
    [forwarder.name, forwarder.contactName, forwarder.phone, forwarder.address]
      .some(value => value.toLocaleLowerCase().includes(search)),
  )
})

async function loadForwarders() {
  loading.value = true
  listError.value = ''
  try {
    forwarders.value = await freightForwarderApi.list()
  } catch {
    listError.value = '货代信息读取失败'
  } finally {
    loading.value = false
  }
}

function openEditor(forwarder?: FreightForwarder) {
  editing.value = forwarder
  Object.assign(form, {
    name: forwarder?.name ?? '',
    contactName: forwarder?.contactName ?? '',
    phone: forwarder?.phone ?? '',
    address: forwarder?.address ?? '',
    remark: forwarder?.remark ?? '',
  })
  submitted.value = false
  saveError.value = ''
  editorVisible.value = true
}

async function saveForwarder() {
  if (saving.value) return
  submitted.value = true
  saveError.value = ''
  const missingField = requiredFields.find(({ key }) => !form[key].trim())
  if (missingField) {
    document.getElementById('forwarder-' + missingField.key)?.focus()
    return
  }

  saving.value = true
  const forwarder = {
    name: form.name.trim(), contactName: form.contactName.trim(), phone: form.phone.trim(),
    address: form.address.trim(), remark: form.remark.trim() || null,
  }
  try {
    if (editing.value) {
      await freightForwarderApi.update(editing.value.id, editing.value.revision, forwarder)
    } else {
      await freightForwarderApi.create(forwarder)
    }
    editorVisible.value = false
    keyword.value = ''
    ElMessage.success(tr('保存成功'))
    await loadForwarders()
  } catch (error) {
    saveError.value = (api.isAxiosError(error) && error.response?.data?.message) || '货代信息保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadForwarders)
</script>

<template>
  <section class="freight-directory">
    <div class="directory-toolbar">
      <el-input v-model="keyword" class="directory-search" clearable
        :placeholder="tr('搜索货代名称、联系人、电话或地址')" :aria-label="tr('搜索货代信息')" />
      <el-button type="primary" @click="openEditor()">{{ tr('新增货代') }}</el-button>
    </div>
    <el-alert v-if="listError" :title="tr(listError)" type="error" :closable="false">
      <el-button link type="primary" :disabled="loading" @click="loadForwarders">{{ tr('重试') }}</el-button>
    </el-alert>
    <div v-loading="loading" class="directory-results">
      <ul v-if="filteredForwarders.length" class="directory-list">
        <li v-for="forwarder in filteredForwarders" :key="forwarder.id">
          <el-card shadow="never" class="directory-card">
            <div class="directory-card-head">
              <h2>{{ forwarder.name }}</h2>
              <el-button :disabled="loading" :aria-label="tr('编辑货代 {name}', { name: forwarder.name })"
                @click="openEditor(forwarder)">{{ tr('编辑') }}</el-button>
            </div>
            <dl>
              <div><dt>{{ tr('联系人') }}</dt><dd>{{ forwarder.contactName }}</dd></div>
              <div><dt>{{ tr('联系电话') }}</dt><dd>{{ forwarder.phone }}</dd></div>
              <div class="directory-full-row"><dt>{{ tr('地址') }}</dt><dd>{{ forwarder.address }}</dd></div>
              <div v-if="forwarder.remark" class="directory-full-row">
                <dt>{{ tr('备注') }}</dt><dd>{{ forwarder.remark }}</dd>
              </div>
            </dl>
          </el-card>
        </li>
      </ul>
      <el-empty v-else-if="!loading && !listError"
        :description="tr(keyword.trim() ? '未找到匹配的货代' : '暂无货代信息')" />
    </div>

    <el-dialog v-model="editorVisible" :title="tr(editing ? '编辑货代' : '新增货代')"
      width="min(540px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-alert v-if="saveError" :title="tr(saveError)" type="error" :closable="false" show-icon />
      <el-form label-position="top" :disabled="saving" @submit.prevent="saveForwarder">
        <el-form-item v-for="field in requiredFields" :key="field.key" :for="'forwarder-' + field.key"
          :label="tr(field.label)" required
          :error="submitted && !form[field.key].trim() ? tr('请填写{field}', { field: tr(field.label) }) : ''">
          <el-input :id="'forwarder-' + field.key" v-model="form[field.key]" :maxlength="field.max"
            :type="field.key === 'address' ? 'textarea' : 'text'" :inputmode="field.key === 'phone' ? 'tel' : undefined" />
        </el-form-item>
        <el-form-item :label="tr('备注')">
          <el-input v-model="form.remark" type="textarea" maxlength="500" />
        </el-form-item>
        <div class="directory-editor-actions">
          <el-button :disabled="saving" @click="editorVisible = false">{{ tr('取消') }}</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">{{ tr('保存') }}</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.freight-directory { display: grid; gap: 16px; }
.directory-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.directory-search { flex: 1 1 240px; max-width: 380px; }
.directory-results { min-height: 112px; }
.directory-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.directory-card { height: 100%; }
.directory-card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.directory-card-head h2 { margin: 0; font-size: 1rem; line-height: 1.5; overflow-wrap: anywhere; }
.directory-card dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 115px), 1fr)); gap: 12px; margin: 16px 0 0; }
.directory-card dt { color: var(--el-text-color-secondary); font-size: .8125rem; margin-bottom: 4px; }
.directory-card dd { margin: 0; line-height: 1.5; overflow-wrap: anywhere; white-space: pre-wrap; }
.directory-full-row { grid-column: 1 / -1; }
.directory-editor-actions { display: flex; justify-content: flex-end; gap: 12px; }
.directory-editor-actions .el-button + .el-button { margin-left: 0; }
</style>
