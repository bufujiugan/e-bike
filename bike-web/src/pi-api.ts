import api from './api'

export type Money = string | number
export type ReservationStatus = 'NONE' | 'TIMED_ACTIVE' | 'PAID_HELD' | 'EXPIRED_REQUIRES_REACQUIRE' | 'RELEASED' | 'FULFILLED' | 'ACTIVE' | 'MIXED'
const reservationLabels: Record<ReservationStatus, string> = {
  NONE: '无来源库存占用', TIMED_ACTIVE: '限时占用中', PAID_HELD: '收款达标保留', MIXED: '各履约订单状态不同',
  EXPIRED_REQUIRES_REACQUIRE: '已到期，需重新占用', RELEASED: '已释放', FULFILLED: '已履约', ACTIVE: '有效占用',
}
export function reservationLabel(status: ReservationStatus | null | undefined, isAgent = false): string {
  if (isAgent && (status === 'TIMED_ACTIVE' || status === 'PAID_HELD')) return reservationLabels.ACTIVE
  return status ? reservationLabels[status] ?? '状态待核对' : '状态待获取'
}
export type PiDocument = {
  sellerName: string; sellerAddress: string; sellerContact: string
  buyerAddress: string; buyerContact: string; bankName: string; bankAccount: string; beneficiary: string
  paymentTerms: string; deliveryTerms: string; templateCode: string; templateVersion: string
  issueDate?: string | null; validUntil?: string | null; shippingAddress?: string | null; transportMode?: string | null
  tradeTerms?: string | null; buyerCountry?: string | null; sellerCountry?: string | null
  buyerContactName?: string | null; buyerPhone?: string | null; buyerEmail?: string | null; businessOwner?: string | null
  sellerContactName?: string | null; sellerPhone?: string | null; sellerEmail?: string | null
}
export type PiTemplate = Pick<PiDocument, 'templateCode' | 'templateVersion' | 'sellerName' | 'sellerAddress' | 'sellerContact' | 'bankName' | 'bankAccount' | 'beneficiary'> & {
  id: number; tradeType: string; layoutCode: string; publishReason: string; publishedAt: string
  sellerCountry: string | null; sellerContactName: string | null; sellerPhone: string | null; sellerEmail: string | null
}
export type PiTemplatePublish = Omit<PiTemplate, 'id' | 'publishedAt' | 'publishReason'> & {
  reason: string; sellerCountry: string; sellerContactName: string; sellerPhone: string; sellerEmail: string
}
export type PiExportJob = { piId: number; sourceType: string; status: string; revision: number; attempts: number; pdfSha256?: string; pdfSize?: number; xlsxSha256?: string; xlsxSize?: number; errorCode?: string; createdAt: string; updatedAt: string }
export type PiConfirmationAttachment = { id: string; piId: number; fileName: string; contentType: string; fileSize: number; sha256: string; visibility: 'CUSTOMER' | 'INTERNAL'; createdAt: string }
export type PiLogisticsAttachment = Omit<PiConfirmationAttachment, 'piId'> & { logisticsId: number }
export type PiItem = {
  id: number; skuId: number; productModelId: number; skuCode: string; productName: string
  specification: string; modelName: string; quantity: number; unitPrice: Money
  unit?: string | null; imageAttachmentId?: string | null; imageSha256?: string | null
  discountAmount: Money; taxAmount: Money; lineTotal: Money
}
export type PiPayment = {
  id: number; amount: Money; currencyCode: string; status: string; receivedAt: string
  voucherObjectKey?: string | null; remark?: string | null
  piId: number; paymentMethod?: string | null; paymentMethodNote?: string | null; recordedAt?: string | null
  confirmedBy?: number | null; revision: number; reversalReason?: string | null; reversedBy?: number | null; reversedAt?: string | null
}
export type PiReceiptAttachment = {
  id: string; paymentId: number; fileName: string; contentType: string; fileSize: number
  sha256: string; createdBy?: number | null; createdAt: string
}
export type PiLogistics = {
  id: number; batchNo: string; carrier?: string | null; trackingNo?: string | null
  status: string; shippedAt?: string | null; remark?: string | null
  stockOutBatchId?: number | null; linkType: string; revision: number; shippedQtySnapshot?: number | null; revokeReason?: string | null
}
export type PiView = {
  revisionSources?: { lockId: number; skuId: number; quantity: number; lockNo: string; skuCode: string; productName: string; expiresAt: string; orderId?: number | null }[]
  businessOwner?: string | null
  previousRevisionId?: number | null
  seriesId?: number; fulfillment?: PiFulfillment[]; dispatchBatches?: PiDispatchBatch[]
  id: number; piNo: string; revision: number; customerId: number; customerCode?: string; customerName: string
  agentId?: number | null; agentName?: string | null; sourceOrderId?: number | null
  reservationStatus?: ReservationStatus | null
  tradeType: string; currencyCode: string; status: string; paymentStatus?: string | null; releaseStatus?: string | null
  plannedShipDate?: string | null; subtotal: Money; discountAmount: Money; taxAmount: Money; freightAmount: Money
  totalAmount: Money; depositRequiredAmount?: Money | null; shipmentRequiredAmount?: Money | null; paidAmount?: Money | null
  otherFeeAmount?: Money | null
  remark?: string | null; createdAt: string; updatedAt: string; releasedAt?: string | null
  document?: PiDocument | null; items: PiItem[]; payments: PiPayment[]; logistics: PiLogistics[]
}
export type PiFulfillment = { orderId: number; orderNo: string; status: string; skuId?: number | null; skuCode?: string | null; orderedQty: number; shippedQty: number; closedQty: number; remainingQty: number; reservationStatus: ReservationStatus }
export type PiDispatchBatch = { id: number; batchNo: string; sourceType: string; executionPiId?: number | null; status: string; originalQty: number; activeQty: number; createdAt: string }
export type PiLockOption = { id: number; lockNo: string; skuId: number; skuCode: string; productName: string; modelId: number; remainingQty: number; expireTime: string }
export type PiMasterOption = { id: number; code: string; name: string }
export type PiSkuOption = { id: number; modelId?: number; productModelId?: number; skuCode: string; productName: string; specification?: string; enabled?: boolean }
export type PiOrderOption = { id: number; orderNo: string; customerId: number; agentId?: number | null; status: string }
export type PiOptions = { customers: PiMasterOption[]; agents: PiMasterOption[]; skus: PiSkuOption[]; orders: PiOrderOption[] }
export type PiCreate = {
  revisionSources?: { lockId: number; quantity: number }[]
  customerId: number; agentId?: number | null; sourceOrderId?: number | null; tradeType: string; currencyCode: string
  plannedShipDate?: string | null; discountAmount: string; taxAmount: string; freightAmount: string
  otherFeeAmount?: string
  depositRequiredAmount: string; shipmentRequiredAmount: string; remark?: string; document: PiDocument
  items: { skuId: number; quantity: number; unitPrice: string; discountAmount: string; taxAmount: string; unit?: string; imageAttachmentId?: string | null }[]
}
export type PiPaymentCreate = { requestId: string; amount: string; currencyCode: string; receivedAt?: string | null; remark?: string; paymentMethod?: string; paymentMethodNote?: string }
export type PiLogisticsCreate = { batchNo: string; carrier?: string; trackingNo?: string; status: string; remark?: string; stockOutBatchId?: number | null; requestId: string; expectedRevision?: number }

export const piApi = {
  async dashboard() { return (await api.get<{ data: Record<string, number | null> }>('/api/pis/dashboard')).data.data },
  async queue(queue: string, params: { keyword?: string; page: number; pageSize: number }) { return (await api.get<{ data: { total: number; records: PiQueueRow[] } }>(`/api/pis/dashboard/${queue}`, { params })).data.data },
  async exportList(params: { keyword?: string; status?: string }) { return (await api.get<Blob>('/api/pis/export.xlsx', { params, responseType: 'blob' })).data },
  async revisions(id: number) { return (await api.get<{ data: PiView[] }>(`/api/pis/${id}/revisions`)).data.data },
  async createRevision(id: number, body: { requestId: string; expectedRevision: number; reason: string }) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/revisions`, body)).data.data },
  async logisticsAttachments(piId: number, logisticsId: number) { return (await api.get<{ data: PiLogisticsAttachment[] }>(`/api/pis/${piId}/logistics/${logisticsId}/attachments`)).data.data },
  async uploadLogisticsAttachment(piId: number, logisticsId: number, requestId: string, visibility: string, file: File, progress: (percent: number) => void) {
    const form = new FormData(); form.append('file', file); form.append('requestId', requestId); form.append('visibility', visibility)
    return (await api.post<{ data: PiLogisticsAttachment }>(`/api/pis/${piId}/logistics/${logisticsId}/attachments`, form, { onUploadProgress: e => { if (e.total) progress(Math.min(99, Math.round(e.loaded * 100 / e.total))) } })).data.data
  },
  async downloadLogisticsAttachment(piId: number, logisticsId: number, id: string) { return (await api.get<Blob>(`/api/pis/${piId}/logistics/${logisticsId}/attachments/${id}/download`, { responseType: 'blob' })).data },
  async confirmationAttachments(id: number) { return (await api.get<{ data: PiConfirmationAttachment[] }>(`/api/pis/${id}/confirmation-attachments`)).data.data },
  async uploadConfirmation(id: number, requestId: string, visibility: string, file: File) { const form = new FormData(); form.append('requestId', requestId); form.append('visibility', visibility); form.append('file', file); return (await api.post<{ data: PiConfirmationAttachment }>(`/api/pis/${id}/confirmation-attachments`, form)).data.data },
  async downloadConfirmation(id: number, attachmentId: string) { return (await api.get<Blob>(`/api/pis/${id}/confirmation-attachments/${attachmentId}/download`, { responseType: 'blob' })).data },
  async itemImage(id: number, itemId: number) { return (await api.get<Blob>(`/api/pis/${id}/items/${itemId}/image`, { responseType: 'blob' })).data },
  async exportTask(id: number) { return (await api.get<{ data: PiExportJob | null }>(`/api/pis/${id}/export-task`)).data.data },
  async retryExport(id: number, revision: number, reason: string) { return (await api.post<{ data: PiExportJob }>(`/api/pis/${id}/export-task/retry`, { revision, reason })).data.data },
  async templates(tradeType: string | undefined, page: number) { return (await api.get<{ data: { records: PiTemplate[]; total: number } }>('/api/pi-templates', { params: { tradeType, page, pageSize: 20 } })).data.data },
  async publishTemplate(body: PiTemplatePublish) { return (await api.post<{ data: PiTemplate }>('/api/pi-templates', body)).data.data },
  async list(params: { keyword?: string; status?: string; page: number; pageSize: number }) {
    return (await api.get<{ data: { records: PiView[]; total: number } }>('/api/pis', { params })).data.data
  },
  async get(id: number) { return (await api.get<{ data: PiView }>(`/api/pis/${id}`)).data.data },
  async batch(id: number, batchId: number) { return (await api.get<{ data: { vehicles: { stockOutId: number; stockOutNo: string; vehicleQrCode: string | null; frameSn: string; skuCode: string; orderNo: string; status: string; cancelReason?: string }[] } }>(`/api/pis/${id}/dispatch-batches/${batchId}`)).data.data },
  async options(keyword?: string) { return (await api.get<{ data: PiOptions }>('/api/pis/options', { params: { keyword: keyword || undefined } })).data.data },
  async create(body: PiCreate) { return (await api.post<{ data: PiView }>('/api/pis', body)).data.data },
  async convert(body: { requestId: string; sources: { lockId: number; quantity: number }[]; pi: PiCreate }) { return (await api.post<{ data: PiView }>('/api/pis/from-locks', body)).data.data },
  async lockOptions(params: { customerId: number; agentId?: number; keyword?: string; page: number }) { return (await api.get<{ data: { records: PiLockOption[]; total: number } }>('/api/pis/lock-options', { params })).data.data },
  async update(id: number, body: PiCreate) { return (await api.put<{ data: PiView }>(`/api/pis/${id}`, body)).data.data },
  async cancel(id: number, reason: string) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/cancel`, { reason })).data.data },
  async confirm(id: number) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/confirm`, {})).data.data },
  async payment(id: number, body: PiPaymentCreate) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/payments`, body)).data.data },
  async reversePayment(id: number, paymentId: number, body: { requestId: string; revision: number; reason: string }) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/payments/${paymentId}/reverse`, body)).data.data },
  async receiptAttachments(piId: number, paymentId: number) {
    return (await api.get<{ data: PiReceiptAttachment[] }>(`/api/pis/${piId}/payments/${paymentId}/attachments`)).data.data
  },
  async uploadReceipt(piId: number, paymentId: number, requestId: string, file: File) {
    const form = new FormData(); form.append('file', file); form.append('requestId', requestId)
    return (await api.post<{ data: PiReceiptAttachment }>(`/api/pis/${piId}/payments/${paymentId}/attachments`, form)).data.data
  },
  async downloadReceipt(piId: number, paymentId: number, attachmentId: string) {
    return (await api.get<Blob>(`/api/pis/${piId}/payments/${paymentId}/attachments/${attachmentId}/download`, { responseType: 'blob' })).data
  },
  async release(id: number, reason: string) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/release`, { reason })).data.data },
  async renewReservation(orderId: number, body: { hours: number; reason: string }) { await api.post(`/api/orders/${orderId}/reservation/renew`, body) },
  async logistics(id: number, body: PiLogisticsCreate) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/logistics`, body)).data.data },
  async updateLogistics(id: number, logisticsId: number, body: PiLogisticsCreate) { return (await api.put<{ data: PiView }>(`/api/pis/${id}/logistics/${logisticsId}`, body)).data.data },
  async withdrawLogistics(id: number, logisticsId: number, body: { requestId: string; expectedRevision: number; reason: string }) { return (await api.post<{ data: PiView }>(`/api/pis/${id}/logistics/${logisticsId}/withdraw`, body)).data.data },
  async logisticsHistory(id: number, logisticsId: number) { return (await api.get<{ data: { revision: number; action: string; snapshot: string; createdAt: string }[] }>(`/api/pis/${id}/logistics/${logisticsId}/history`)).data.data },
  async download(id: number, format: 'xlsx' | 'pdf') { return (await api.get<Blob>(`/api/pis/${id}/export.${format}`, { responseType: 'blob' })).data },
}

export type PiQueueRow = { recordType: 'PI' | 'ORDER' | 'LOCK'; id: number; piId?: number; referenceNo: string; customerName?: string; status: string; expiresAt?: string; createdAt: string; revision?: number; orderedQty: number; shippedQty: number; remainingQty: number; businessOwner?: string }
