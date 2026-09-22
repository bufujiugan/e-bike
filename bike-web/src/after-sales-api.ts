import api from './api'
import type { CompanyContact } from './after-sales-company-api'

export type CaseStatus = 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'NEEDS_INFO' | 'APPROVED' | 'IN_PROGRESS' | 'WAITING_PARTS' | 'SHIPPED' | 'RESOLVED' | 'CLOSED' | 'REJECTED'
export const caseStatuses: Record<CaseStatus, string> = { DRAFT: '待提交', SUBMITTED: '已提交', UNDER_REVIEW: '审核中', NEEDS_INFO: '等待补充资料', APPROVED: '已审批', IN_PROGRESS: '处理中', WAITING_PARTS: '待发货', SHIPPED: '已发货', RESOLVED: '已解决', CLOSED: '已归档', REJECTED: '已拒绝' }
export const warrantyNames: Record<string, string> = { PENDING: '待确认', UNKNOWN: '未知', IN_WARRANTY: '保修期内', OUT_OF_WARRANTY: '保修期外' }
export type CaseDraft = {
  customerId: number | null; agentId: number | null; vehicleId: number | null; frameSn: string; productModelId: number | null;
  motorSn: string; batterySn: string; displaySn: string; purchaseDate: string | null; failureTime: string | null;
  faultType: string; faultPart: string; errorCode: string; description: string; attemptedRepair: string; warrantyStatus: string;
  contact: CompanyContact | null; endCustomerPurchaseDate: string | null;
}
export type CaseMessage = { id: number; caseId: number; authorId: number | null; visibility: 'CUSTOMER' | 'INTERNAL'; body: string; createdAt: string }
export type CaseShipment = { id: number; caseId: number; partName: string; quantity: number; carrier: string | null; trackingNo: string | null; status: 'PLANNED' | 'SHIPPED' | 'CANCELLED'; shippedAt: string | null; oldPartReturnRequired: boolean; oldPartReturnReceivedAt: string | null; createdAt: string; solutionItemId: number | null; approvalSource: 'VERSIONED' | 'LEGACY_UNLINKED' | 'LEGACY_REVIEWED'; revision: number; partCode: string | null; warehouseName: string | null; recipient: string | null; phone: string | null; countryCode: string | null; region: string | null; city: string | null; addressLine: string | null; postalCode: string | null; expectedArrivalDate: string | null; logisticsStatus: 'IN_TRANSIT' | 'EXCEPTION' | 'DELIVERED' | null; logisticsNote: string | null; deliveredAt: string | null; cancelledReason: string | null }
export type CaseHistory = { id: number; action: string; operatorName: string; operationTime: string }
export type CaseAttachment = { id: number; caseId: number; messageId: number | null; fileName: string; contentType: string; fileSize: number; sha256: string; visibility: 'CUSTOMER' | 'INTERNAL'; category: string; uploadedBy: number | null; createdAt: string }
export type AfterSalesCase = CaseDraft & {
  id: number; caseNo: string; customerName: string | null; agentName: string | null; modelName: string | null; status: CaseStatus;
  assigneeId: number | null; technicalJudgment: string | null; solution: string | null; internalNote: string | null; rejectionReason: string | null;
  createdAt: string; updatedAt: string; submittedAt: string | null; closedAt: string | null;
  messages: CaseMessage[]; shipments: CaseShipment[]; history: CaseHistory[];
  companyName: string | null; contactName: string | null; contactPhone: string | null; contactEmail: string | null; countryCode: string | null;
  region: string | null; city: string | null; addressLine: string | null; postalCode: string | null; snapshotSource: string | null; assigneeName: string | null;
  solutionVersion: SolutionVersion | null;
}
export type SolutionPart = { partCode: string; partName: string; quantity: number; unitCharge: string | number; returnRequired: boolean }
export type SolutionItem = Omit<SolutionPart, 'unitCharge'> & { unitCharge: string | number | null; id: number; versionId: number; allocatedQty: number }
export type SolutionDraft = { technicalJudgment: string; solution: string; internalNote: string; previousVersionId: number | null; faultCause: string; customerActions: string; repairNotes: string; warrantyStatus: string; currency: string; chargeReason: string; parts: SolutionPart[] }
export type SolutionVersion = { id: number; revision: number; status: 'DRAFT' | 'CONFIRMED' | 'APPROVED' | 'SUPERSEDED' | 'LEGACY'; document: SolutionDraft; parts: SolutionItem[]; createdAt: string; confirmedAt: string | null; approvedAt: string | null }
export type ShippingDetails = { warehouseName: string; recipient: string; phone: string; countryCode: string; region: string; city: string; addressLine: string; postalCode: string; expectedArrivalDate: string | null; shippedAt: string | null }
export const blankShipping = (): ShippingDetails => ({ warehouseName: '', recipient: '', phone: '', countryCode: '', region: '', city: '', addressLine: '', postalCode: '', expectedArrivalDate: null, shippedAt: null })
export function shippingFromShipment(row: CaseShipment): ShippingDetails { const result = blankShipping(); for (const key of Object.keys(result) as (keyof ShippingDetails)[]) Object.assign(result, { [key]: row[key] ?? result[key] }); return result }
export type LogisticsRequest = { revision: number; action: 'SAVE' | 'TRACK' | 'CORRECT' | 'CANCEL'; shipping?: ShippingDetails; carrier?: string; trackingNo?: string; logisticsStatus?: string; note: string; expectedArrivalDate?: string | null }
export type ShipmentDraft = { partName: string; quantity: number; carrier: string; trackingNo: string; oldPartReturnRequired: boolean; solutionItemId: number | null; shipping?: ShippingDetails }
export type CaseOption = { id: number; name: string }
export type CaseOptions = { customers: CaseOption[]; agents: CaseOption[]; models: CaseOption[] }
export type CaseQueue = '' | 'PENDING' | 'REVIEW' | 'WAITING_CUSTOMER' | 'PROCESSING' | 'WAITING_SHIPMENT' | 'SHIPPED' | 'COMPLETED' | 'COMPLETED_MONTH'
export type CaseFilters = { companyName: string; contactName: string; frameSn: string; modelName: string; faultType: string; countryCode: string; assigneeName: string; createdFrom: string | null; createdTo: string | null; queue: CaseQueue }
export type CaseDashboard = { total: number; pending: number; review: number; waitingCustomer: number; processing: number; waitingShipment: number; shipped: number; completed: number; completedThisMonth: number }
export type ReturnStatus = 'REQUIRED' | 'WAITING_CUSTOMER' | 'IN_TRANSIT' | 'RECEIVED' | 'INSPECTED' | 'SCRAPPED' | 'COMPLETED' | 'WAIVED' | 'CANCELLED'
export type ReturnAction = 'REQUEST' | 'SEND' | 'RECEIVE' | 'INSPECT' | 'SCRAP' | 'COMPLETE' | 'WAIVE'
export type CaseReturn = { shipmentId: number; caseId: number; partName: string; quantity: number; status: ReturnStatus; revision: number; warehouseName: string | null; recipient: string | null; phone: string | null; addressLine: string | null; postalCode: string | null; carrier: string | null; trackingNo: string | null; sentAt: string | null; receivedAt: string | null; inspectedAt: string | null; inspectionResult: string | null; dispositionNote: string | null; completedAt: string | null; updatedAt: string }
export type ReturnRequest = { revision: number; action: ReturnAction; warehouseName: string; recipient: string; phone: string; addressLine: string; postalCode: string; carrier: string; trackingNo: string; inspectionResult: string; reason: string }
export type CaseNotification = { id: number; eventType: string; audience: 'CUSTOMER' | 'STAFF'; originalRecipient: string | null; recipient: string | null; status: 'PENDING' | 'SENDING' | 'RETRY' | 'SENT' | 'FAILED' | 'BLOCKED'; attemptCount: number; revision: number; lastError: string | null; nextAttemptAt: string; sentAt: string | null; createdAt: string }
export type NotificationPage = { notifications: Page<CaseNotification>; sendingEnabled: boolean; smtpConfigured: boolean }
type Envelope<T> = { code: string; message: string; data: T }
type Page<T> = { records: T[]; total: number; page: number; pageSize: number }
const endpoint = '/api/after-sales/cases'
function data<T>(response: { data: Envelope<T> }): T {
  if (response.data.code !== 'SUCCESS') throw new Error(response.data.message || '售后操作失败')
  return response.data.data
}
export function afterSalesError(error: unknown): string {
  if (api.isAxiosError(error)) return error.response?.data?.message || '售后请求失败，请重试'
  return error instanceof Error ? error.message : '售后操作失败'
}
export const afterSales = {
  notifications: async (id: number, page: number) => data(await api.get<Envelope<NotificationPage>>(`${endpoint}/${id}/notifications`, { params: { page, pageSize: 20 } })),
  retryNotification: async (id: number, notificationId: number, revision: number, recipient: string | undefined, reason: string) => data(await api.post<Envelope<CaseNotification>>(`${endpoint}/${id}/notifications/${notificationId}/retry`, { revision, recipient, reason })),
  returns: async (id: number) => data(await api.get<Envelope<CaseReturn[]>>(`${endpoint}/${id}/returns`)),
  returnAction: async (id: number, shipmentId: number, request: ReturnRequest) => data(await api.post<Envelope<CaseReturn>>(`${endpoint}/${id}/returns/${shipmentId}`, request)),
  attachments: async (id: number) => data(await api.get<Envelope<CaseAttachment[]>>(`${endpoint}/${id}/attachments`)),
  upload: async (id: number, files: File[], visibility: 'CUSTOMER' | 'INTERNAL', category: string, messageId?: number) => {
    const form = new FormData(); files.forEach(file => form.append('files', file))
    return data(await api.post<Envelope<CaseAttachment[]>>(`${endpoint}/${id}/attachments`, form, { params: { visibility, category, messageId } }))
  },
  download: async (id: number, attachmentId: number): Promise<Blob> => {
    try { return (await api.get<Blob>(`${endpoint}/${id}/attachments/${attachmentId}/download`, { responseType: 'blob' })).data }
    catch (error) {
      if (api.isAxiosError(error) && error.response?.data instanceof Blob) {
        try { const body = JSON.parse(await error.response.data.text()); throw new Error(body.message || '附件下载失败') }
        catch (parseError) { if (parseError instanceof SyntaxError) throw new Error('附件下载失败'); throw parseError }
      }
      throw error
    }
  },
  options: async () => data(await api.get<Envelope<CaseOptions>>(`${endpoint}/options`)),
  list: async (keyword: string, status: string, page: number, filters?: CaseFilters) => data(await api.get<Envelope<Page<AfterSalesCase>>>(endpoint, { params: { ...filters, keyword, status, page, pageSize: 20 } })),
  dashboard: async (keyword: string, filters: CaseFilters) => data(await api.get<Envelope<CaseDashboard>>(`${endpoint}/dashboard`, { params: { ...filters, keyword } })),
  get: async (id: number) => data(await api.get<Envelope<AfterSalesCase>>(`${endpoint}/${id}`)),
  create: async (draft: CaseDraft) => data(await api.post<Envelope<AfterSalesCase>>(endpoint, draft)),
  update: async (id: number, draft: CaseDraft) => data(await api.put<Envelope<AfterSalesCase>>(`${endpoint}/${id}`, draft)),
  submit: async (id: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/submit`)),
  review: async (id: number, action: 'START' | 'APPROVE' | 'REJECT' | 'REQUEST_INFO' | 'REVISE', reason = '', solutionVersionId?: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/review`, { action, reason, solutionVersionId })),
  solution: async (id: number, draft: SolutionDraft) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/solution`, draft)),
  confirmSolution: async (id: number, versionId: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/solution/confirm`, { versionId })),
  solutionVersions: async (id: number) => data(await api.get<Envelope<SolutionVersion[]>>(`${endpoint}/${id}/solution/versions`)),
  reconcileShipment: async (id: number, shipmentId: number, itemId: number, reason: string) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/shipments/${shipmentId}/approval-link`, { itemId, reason })),
  process: async (id: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/process`)),
  message: async (id: number, body: string, visibility: 'CUSTOMER' | 'INTERNAL') => data(await api.post<Envelope<CaseMessage>>(`${endpoint}/${id}/messages`, { body, visibility })),
  shipment: async (id: number, draft: ShipmentDraft) => data(await api.post<Envelope<CaseShipment>>(`${endpoint}/${id}/shipments`, draft)),
  dispatch: async (id: number, shipmentId: number, carrier: string, trackingNo: string, shipping: ShippingDetails, revision: number) => data(await api.post<Envelope<CaseShipment>>(`${endpoint}/${id}/shipments/${shipmentId}/dispatch`, { carrier, trackingNo, shipping, revision })),
  logistics: async (id: number, shipmentId: number, request: LogisticsRequest) => data(await api.post<Envelope<CaseShipment>>(`${endpoint}/${id}/shipments/${shipmentId}/logistics`, request)),
  resolve: async (id: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/resolve`)),
  close: async (id: number) => data(await api.post<Envelope<AfterSalesCase>>(`${endpoint}/${id}/close`)),
}
