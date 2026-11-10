import type { PiDocument, PiLockOption, PiOptions } from './pi-api'

export type DraftItem = { key: string; skuId: number | undefined; quantity: number; unitPrice: string; discountAmount: string; taxAmount: string; unit: string; imageAttachmentId?: string | null }
export type PiDraftForm = {
  customerId?: number; agentId?: number; sourceOrderId?: number
  tradeType: string; currencyCode: string; plannedShipDate: string
  discountAmount: string; taxAmount: string; freightAmount: string; otherFeeAmount: string
  depositRequiredAmount: string; shipmentRequiredAmount: string; remark: string
  document: PiDocument; items: DraftItem[]
}
export type PiLocalDraftContent = {
  form: PiDraftForm; step: number; editingId: number | null; fromLocks: boolean
  selectedLocks: Array<PiLockOption & { quantity: number }>; options: PiOptions
  baseline: string; baseUpdatedAt: string | null; customerLoadFailed: boolean
  conversionKey: string; conversionFingerprint: string
}
export type PiLocalDraft = { id: string; savedAt: string; content: PiLocalDraftContent; serialized: string }

const object = (value: unknown): value is Record<string, any> => value !== null && typeof value === 'object' && !Array.isArray(value)
const optionalId = (value: unknown) => value == null || typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const textFields = ['tradeType', 'currencyCode', 'discountAmount', 'taxAmount', 'freightAmount', 'otherFeeAmount', 'depositRequiredAmount', 'shipmentRequiredAmount', 'remark']
const documentFields = ['sellerName', 'sellerAddress', 'sellerContact', 'buyerAddress', 'buyerContact', 'bankName', 'bankAccount', 'beneficiary', 'paymentTerms', 'deliveryTerms', 'templateCode', 'templateVersion']

// 本机草稿允许业务字段未填完整；只检查存储结构，正式提交仍经过原有业务校验。
function isContent(value: unknown): value is PiLocalDraftContent {
  if (!object(value) || !object(value.form)) return false
  const form = value.form, document = form.document
  return textFields.every(key => typeof form[key] === 'string')
    && (form.plannedShipDate == null || typeof form.plannedShipDate === 'string')
    && ['customerId', 'agentId', 'sourceOrderId'].every(key => optionalId(form[key]))
    && object(document) && documentFields.every(key => typeof document[key] === 'string')
    && Object.entries(document).every(([key, field]) => key === 'business' || field == null || typeof field === 'string')
    && (document.business == null || object(document.business) && optionalId(document.business.warehouseId)
      && optionalId(document.business.fulfillmentProfileId) && typeof document.business.collectionEntity === 'string'
      && (document.business.domesticShipment == null || typeof document.business.domesticShipment === 'boolean'))
    && Array.isArray(form.items) && form.items.every(item => object(item) && typeof item.key === 'string'
      && optionalId(item.skuId) && (item.quantity == null || typeof item.quantity === 'number')
      && ['unitPrice', 'discountAmount', 'taxAmount', 'unit'].every(key => typeof item[key] === 'string'))
    && Number.isInteger(value.step) && value.step >= 0 && value.step <= 2
    && optionalId(value.editingId) && typeof value.fromLocks === 'boolean'
    && typeof value.baseline === 'string' && (value.baseUpdatedAt === null || typeof value.baseUpdatedAt === 'string')
    && typeof value.customerLoadFailed === 'boolean' && typeof value.conversionKey === 'string' && typeof value.conversionFingerprint === 'string'
    && Array.isArray(value.selectedLocks) && value.selectedLocks.every(lock => object(lock)
      && optionalId(lock.id) && lock.id != null && optionalId(lock.skuId) && lock.skuId != null && (lock.quantity == null || typeof lock.quantity === 'number'))
    && object(value.options) && ['customers', 'agents', 'skus', 'orders'].every(key => Array.isArray(value.options[key])
      && value.options[key].every((option: unknown) => object(option) && optionalId(option.id) && option.id != null))
}

export function createPiLocalDraftStore(storage: Storage, username: string) {
  if (!username.trim()) throw new Error('无法识别当前账号，暂不能保存本机草稿')
  const prefix = `bike:pi-local-draft:v1:${encodeURIComponent(username)}:`
  const key = (id: string) => prefix + encodeURIComponent(id)
  function read(id: string): PiLocalDraft | null {
    const serialized = storage.getItem(key(id))
    if (serialized == null) return null
    let record: unknown
    try { record = JSON.parse(serialized) } catch { throw new Error('本机草稿格式损坏，已保留原内容，请勿清理浏览器数据') }
    if (!object(record) || record.version !== 1 || typeof record.savedAt !== 'string'
      || !Number.isFinite(Date.parse(record.savedAt)) || !isContent(record.content)) {
      throw new Error('本机草稿版本或格式不兼容，已保留原内容')
    }
    return { id, savedAt: record.savedAt, content: record.content, serialized }
  }
  return {
    read,
    list() {
      const records: PiLocalDraft[] = [], errors: string[] = []
      for (let index = 0; index < storage.length; index++) {
        const entry = storage.key(index)
        if (!entry?.startsWith(prefix)) continue
        try { const record = read(decodeURIComponent(entry.slice(prefix.length))); if (record) records.push(record) }
        catch (error) { errors.push(error instanceof Error ? error.message : '读取本机草稿失败') }
      }
      return { records: records.sort((a, b) => b.savedAt.localeCompare(a.savedAt)), errors }
    },
    save(id: string, content: PiLocalDraftContent, expected: string | null) {
      if (storage.getItem(key(id)) !== expected) throw new Error('该草稿已在其他页面修改，请保留当前页面并重新打开最新草稿')
      const record = { version: 1, savedAt: new Date().toISOString(), content }
      const serialized = JSON.stringify(record)
      if (!isContent(JSON.parse(serialized).content)) throw new Error('草稿内容格式异常，未覆盖已保存内容，请保留当前页面')
      storage.setItem(key(id), serialized)
      return { id, savedAt: record.savedAt, content, serialized }
    },
    remove(record: PiLocalDraft) {
      if (storage.getItem(key(record.id)) !== record.serialized) throw new Error('该草稿已在其他页面修改，请刷新草稿列表后再操作')
      storage.removeItem(key(record.id))
    },
  }
}
