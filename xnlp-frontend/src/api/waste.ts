const BASE = '/api/v1/waste'

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const isMultipart = options?.body instanceof FormData
  const response = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...(isMultipart ? {} : { 'Content-Type': 'application/json' }), ...(options?.headers as Record<string, string> || {}) },
  })
  if (!response.ok) {
    const raw = await response.text()
    let message = raw
    try { message = JSON.parse(raw)?.message || raw } catch { /* plain-text error */ }
    throw new Error(message || `${response.status} 请求失败`)
  }
  return response.status === 204 ? undefined as T : response.json()
}

export type Vehicle = {
  id: string; plate_no: string; vehicle_type: string; company_name: string
  driver_name: string; driver_phone: string; verified: boolean
}

export const wasteApi = {
  dashboard: () => request<Record<string, number>>('/dashboard'),
  vehicles: () => request<Vehicle[]>('/vehicles'),
  applications: (status = '') => request<any[]>(`/applications${status ? `?status=${encodeURIComponent(status)}` : ''}`),
  application: (id: string) => request<any>(`/applications/${id}`),
  audits: (id: string) => request<any[]>(`/applications/${id}/audits`),
  createApplication: (payload: Record<string, unknown>) => request<any>('/applications', { method: 'POST', body: JSON.stringify(payload) }),
  review: (id: string, approve: boolean, comment: string) => request<any>(`/applications/${id}/review`, { method: 'POST', body: JSON.stringify({ approve, comment, reviewer: '平台审核员' }) }),
  refreshCode: (id: string) => request<any>(`/applications/${id}/refresh-code`, { method: 'POST' }),
  uploadPhoto: (file: File) => { const body = new FormData(); body.append('file', file); return request<{ url: string; filename: string }>('/photos', { method: 'POST', body }) },
  verifyGate: (code: string, plateNo: string) => request<any>('/gate/verify', { method: 'POST', body: JSON.stringify({ code, plateNo }) }),
  addWeighing: (payload: Record<string, unknown>) => request<any>('/weighings', { method: 'POST', body: JSON.stringify(payload) }),
  ledger: (plateNo = '', eventType = '') => request<any[]>(`/ledger?plateNo=${encodeURIComponent(plateNo)}&eventType=${encodeURIComponent(eventType)}`),
}
