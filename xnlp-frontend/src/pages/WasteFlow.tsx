import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowRight,
  CheckCircle2,
  ClipboardCheck,
  Clock3,
  FileCheck2,
  Gauge,
  ImagePlus,
  Loader2,
  MapPin,
  RefreshCw,
  ShieldCheck,
  Truck,
  XCircle,
} from 'lucide-react'
import { wasteApi, type Vehicle } from '../api/waste'

type Application = Record<string, any>
type Dashboard = Record<string, number>

type ApplicationForm = {
  wasteType: string
  clearReason: string
  pickupLocation: string
  estimatedWeight: string
  vehicleId: string
  processingSite: string
  routeDescription: string
  orderSubject: string
  subjectName: string
  contactName: string
  contactPhone: string
}

const initialForm: ApplicationForm = {
  wasteType: '工程渣土',
  clearReason: '基坑开挖土方外运',
  pickupLocation: '广州市天河区科韵路项目现场',
  estimatedWeight: '12',
  vehicleId: '',
  processingSite: '广州建筑废弃物消纳场',
  routeDescription: '科韵路 → 黄埔大道 → 消纳场',
  orderSubject: '企业',
  subjectName: 'X-NLP 工程项目',
  contactName: '项目管理员',
  contactPhone: '13800138000',
}

const statusStyles: Record<string, string> = {
  PENDING: 'bg-amber-50 text-amber-700 ring-amber-200',
  APPROVED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  REJECTED: 'bg-rose-50 text-rose-700 ring-rose-200',
  COMPLETED: 'bg-slate-100 text-slate-600 ring-slate-200',
}

const formatDate = (value: unknown) => {
  if (!value) return '—'
  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

const toNumber = (value: unknown) => Number(value || 0)

export default function WasteFlow() {
  const { t } = useTranslation()
  const [dashboard, setDashboard] = useState<Dashboard>({})
  const [vehicles, setVehicles] = useState<Vehicle[]>([])
  const [applications, setApplications] = useState<Application[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [form, setForm] = useState<ApplicationForm>(initialForm)
  const [photoFiles, setPhotoFiles] = useState<File[]>([])
  const [reviewComment, setReviewComment] = useState('')
  const [gateCode, setGateCode] = useState('')
  const [gatePlate, setGatePlate] = useState('')
  const [gateResult, setGateResult] = useState<Application | null>(null)
  const [weighing, setWeighing] = useState({ eventType: 'INBOUND', grossWeight: '', tareWeight: '', weighbridgeNo: '', operatorName: '' })
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const selected = useMemo(() => applications.find(item => item.id === selectedId) || null, [applications, selectedId])
  const approvedApplications = useMemo(() => applications.filter(item => item.status === 'APPROVED'), [applications])
  const filteredApplications = useMemo(() => statusFilter ? applications.filter(item => item.status === statusFilter) : applications, [applications, statusFilter])

  const load = async (filter = statusFilter) => {
    setLoading(true)
    setError('')
    try {
      const [summary, vehicleList, applicationList] = await Promise.all([
        wasteApi.dashboard(),
        wasteApi.vehicles(),
        wasteApi.applications(filter),
      ])
      setDashboard(summary)
      setVehicles(vehicleList)
      setApplications(applicationList)
      setSelectedId(current => applicationList.some(item => item.id === current) ? current : applicationList[0]?.id || '')
      setForm(current => ({ ...current, vehicleId: current.vehicleId || vehicleList[0]?.id || '' }))
    } catch (e: any) {
      setError(e.message || t('waste.requestFailed'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load('') }, [])

  const runAction = async (key: string, action: () => Promise<void>) => {
    setBusy(key)
    setError('')
    setNotice('')
    try {
      await action()
    } catch (e: any) {
      setError(e.message || t('waste.requestFailed'))
    } finally {
      setBusy('')
    }
  }

  const updateForm = (key: keyof ApplicationForm, value: string) => setForm(current => ({ ...current, [key]: value }))

  const submitApplication = async (event: FormEvent) => {
    event.preventDefault()
    if (photoFiles.length < 2) {
      setError(t('waste.photoHint'))
      return
    }
    await runAction('create', async () => {
      const uploads = await Promise.all(photoFiles.map(file => wasteApi.uploadPhoto(file)))
      const created = await wasteApi.createApplication({
        ...form,
        estimatedWeight: Number(form.estimatedWeight),
        photoUrls: uploads.map(item => item.url).join(','),
      })
      setForm({ ...initialForm, vehicleId: vehicles[0]?.id || '' })
      setPhotoFiles([])
      setSelectedId(created.id)
      setNotice(t('waste.created'))
      await load(statusFilter)
    })
  }

  const review = async (approve: boolean) => {
    if (!selected) return
    await runAction(approve ? 'approve' : 'reject', async () => {
      await wasteApi.review(selected.id, approve, reviewComment)
      setReviewComment('')
      setNotice(approve ? t('waste.approved') : t('waste.rejected'))
      await load(statusFilter)
    })
  }

  const refreshCode = async () => {
    if (!selected) return
    await runAction('refresh', async () => {
      await wasteApi.refreshCode(selected.id)
      setNotice(t('waste.codeRefreshed'))
      await load(statusFilter)
    })
  }

  const verifyGate = async (event: FormEvent) => {
    event.preventDefault()
    await runAction('gate', async () => {
      setGateResult(await wasteApi.verifyGate(gateCode, gatePlate))
    })
  }

  const addWeighing = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected) return
    await runAction('weighing', async () => {
      await wasteApi.addWeighing({ applicationId: selected.id, ...weighing, grossWeight: Number(weighing.grossWeight), tareWeight: Number(weighing.tareWeight) })
      setNotice(t('waste.weighingSaved'))
      setWeighing({ eventType: weighing.eventType, grossWeight: '', tareWeight: '', weighbridgeNo: weighing.weighbridgeNo, operatorName: weighing.operatorName })
      await load(statusFilter)
    })
  }

  const statusLabel = (status: string) => t(`waste.status.${status}`, { defaultValue: status })
  const selectedVehicle = selected ? vehicles.find(item => item.id === selected.vehicle_id) : null

  return (
    <div className="space-y-6">
      <section className="relative overflow-hidden rounded-[28px] bg-[#0b1220] px-6 py-7 text-white shadow-2xl shadow-slate-900/10 sm:px-8">
        <div className="absolute -right-16 -top-24 h-64 w-64 rounded-full bg-cyan-400/20 blur-3xl" />
        <div className="relative flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-3xl">
            <div className="eyebrow text-cyan-300">{t('waste.eyebrow')}</div>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">{t('waste.title')}</h1>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-300">{t('waste.subtitle')}</p>
          </div>
          <div className="flex items-center gap-3 text-xs text-slate-300">
            <span className="flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-2"><ShieldCheck className="h-4 w-4 text-emerald-300" /> {t('waste.auditReady')}</span>
            <button onClick={() => load(statusFilter)} className="rounded-xl border border-white/15 p-2.5 transition hover:bg-white/10" aria-label={t('common.refresh')}>
              <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
          </div>
        </div>
      </section>

      {error && <div className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {notice && <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</div>}

      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-6">
        <Kpi icon={ClipboardCheck} label={t('waste.kpi.total')} value={dashboard.total_applications} tone="blue" />
        <Kpi icon={Clock3} label={t('waste.kpi.pending')} value={dashboard.pending_applications} tone="amber" />
        <Kpi icon={CheckCircle2} label={t('waste.kpi.approved')} value={dashboard.approved_applications} tone="emerald" />
        <Kpi icon={Truck} label={t('waste.kpi.inbound')} value={dashboard.inbound_count} tone="cyan" />
        <Kpi icon={Gauge} label={t('waste.kpi.outbound')} value={dashboard.outbound_count} tone="violet" />
        <Kpi icon={FileCheck2} label={t('waste.kpi.cleared')} value={`${toNumber(dashboard.total_cleared_tons).toFixed(2)} t`} tone="slate" />
      </section>

      <section className="grid items-start gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(380px,.85fr)]">
        <div className="surface overflow-hidden">
          <div className="flex flex-col gap-4 border-b border-slate-100 px-5 py-5 sm:flex-row sm:items-end sm:justify-between">
            <div><div className="eyebrow">{t('waste.operations')}</div><h2 className="mt-1 text-xl font-semibold text-slate-900">{t('waste.applications')}</h2></div>
            <div className="flex items-center gap-2">
              <select className="input !w-auto !py-2 text-xs" value={statusFilter} onChange={event => { setStatusFilter(event.target.value); load(event.target.value) }}>
                <option value="">{t('waste.allStatuses')}</option>
                {['PENDING', 'APPROVED', 'REJECTED', 'COMPLETED'].map(status => <option key={status} value={status}>{statusLabel(status)}</option>)}
              </select>
              <span className="rounded-full bg-slate-100 px-3 py-2 text-xs font-semibold text-slate-500">{filteredApplications.length}</span>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="data-table min-w-[700px]">
              <thead><tr><th>{t('waste.applicationNo')}</th><th>{t('waste.subject')}</th><th>{t('waste.vehicle')}</th><th>{t('waste.weight')}</th><th>{t('common.status')}</th><th>{t('waste.createdAt')}</th></tr></thead>
              <tbody>
                {filteredApplications.map(item => <tr key={item.id} onClick={() => setSelectedId(item.id)} className={`cursor-pointer transition hover:bg-slate-50 ${selectedId === item.id ? 'bg-blue-50/60' : ''}`}>
                  <td><div className="font-semibold text-slate-800">{item.application_no}</div><div className="mt-1 max-w-[180px] truncate text-xs text-slate-400">{item.waste_type}</div></td>
                  <td><div className="font-medium text-slate-700">{item.subject_name}</div><div className="mt-1 text-xs text-slate-400">{item.pickup_location}</div></td>
                  <td><div className="font-medium text-slate-700">{item.plate_no}</div><div className="mt-1 text-xs text-slate-400">{item.driver_name}</div></td>
                  <td><div className="font-semibold text-slate-700">{toNumber(item.estimated_weight_tons).toFixed(2)} t</div><div className="mt-1 text-xs text-slate-400">{t('waste.remaining', { value: toNumber(item.remaining_weight_tons).toFixed(2) })}</div></td>
                  <td><span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ${statusStyles[item.status] || 'bg-slate-100 text-slate-600 ring-slate-200'}`}>{statusLabel(item.status)}</span></td>
                  <td className="whitespace-nowrap text-xs text-slate-400">{formatDate(item.created_at)}</td>
                </tr>)}
                {!loading && filteredApplications.length === 0 && <tr><td colSpan={6} className="py-14 text-center text-sm text-slate-400">{t('waste.emptyApplications')}</td></tr>}
              </tbody>
            </table>
          </div>
        </div>

        <div className="surface p-5 sm:p-6">
          <div className="flex items-start justify-between gap-3">
            <div><div className="eyebrow">{t('waste.detailEyebrow')}</div><h2 className="mt-1 text-xl font-semibold text-slate-900">{selected?.application_no || t('waste.selectApplication')}</h2></div>
            {selected && <span className={`inline-flex shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ${statusStyles[selected.status] || 'bg-slate-100 text-slate-600 ring-slate-200'}`}>{statusLabel(selected.status)}</span>}
          </div>
          {selected ? <div className="mt-5 space-y-5">
            <div className="grid grid-cols-2 gap-3 text-sm">
              <Detail label={t('waste.subject')} value={selected.subject_name} />
              <Detail label={t('waste.vehicle')} value={`${selected.plate_no} · ${selected.driver_name}`} />
              <Detail label={t('waste.pickup')} value={selected.pickup_location} />
              <Detail label={t('waste.site')} value={selected.processing_site} />
              <Detail label={t('waste.contact')} value={`${selected.contact_name} ${selected.contact_phone}`} />
              <Detail label={t('waste.remainingWeight')} value={`${toNumber(selected.remaining_weight_tons).toFixed(2)} t`} />
            </div>
            {selected.status === 'PENDING' && <div className="rounded-2xl border border-amber-100 bg-amber-50/60 p-4">
              <label className="text-xs font-semibold text-amber-800">{t('waste.reviewComment')}</label>
              <textarea className="input mt-2 min-h-20 resize-y" value={reviewComment} onChange={event => setReviewComment(event.target.value)} placeholder={t('waste.reviewPlaceholder')} />
              <div className="mt-3 flex gap-2"><button disabled={!!busy} onClick={() => review(false)} className="flex-1 rounded-xl border border-rose-200 bg-white px-3 py-2.5 text-sm font-semibold text-rose-700 transition hover:bg-rose-50 disabled:opacity-50">{busy === 'reject' && <Loader2 className="mr-2 inline h-4 w-4 animate-spin" />}{t('waste.reject')}</button><button disabled={!!busy} onClick={() => review(true)} className="flex-1 rounded-xl bg-slate-950 px-3 py-2.5 text-sm font-semibold text-white transition hover:bg-blue-700 disabled:opacity-50">{busy === 'approve' && <Loader2 className="mr-2 inline h-4 w-4 animate-spin" />}{t('waste.approve')}</button></div>
            </div>}
            {selected.status === 'APPROVED' && <div className="rounded-2xl border border-emerald-100 bg-emerald-50/60 p-4">
              <div className="flex items-center justify-between"><span className="text-xs font-semibold uppercase tracking-wider text-emerald-700">{t('waste.authorizationCode')}</span><button onClick={refreshCode} disabled={!!busy} className="icon-btn text-emerald-700" aria-label={t('waste.refreshCode')}><RefreshCw className={`h-4 w-4 ${busy === 'refresh' ? 'animate-spin' : ''}`} /></button></div>
              <div className="mt-2 flex items-end justify-between gap-3"><div className="font-mono text-3xl font-bold tracking-[.2em] text-emerald-950">{selected.code || '------'}</div><div className="text-right text-xs text-emerald-700">{t('waste.expiresAt')}<br /><b>{formatDate(selected.code_expires_at)}</b></div></div>
            </div>}
            {selectedVehicle && <div className="flex items-center gap-3 rounded-2xl bg-slate-50 p-4"><div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white text-blue-600 shadow-sm"><Truck className="h-5 w-5" /></div><div className="min-w-0"><div className="text-sm font-semibold text-slate-800">{selectedVehicle.company_name}</div><div className="mt-1 text-xs text-slate-400">{selectedVehicle.vehicle_type} · {selectedVehicle.driver_phone}</div></div></div>}
          </div> : <div className="flex min-h-64 flex-col items-center justify-center text-center text-sm text-slate-400"><ClipboardCheck className="mb-3 h-10 w-10 text-slate-200" />{t('waste.selectHint')}</div>}
        </div>
      </section>

      <section className="grid items-start gap-6 xl:grid-cols-2">
        <form onSubmit={submitApplication} className="surface p-5 sm:p-6">
          <div className="eyebrow">{t('waste.newEyebrow')}</div><h2 className="mt-1 text-xl font-semibold text-slate-900">{t('waste.newApplication')}</h2><p className="mt-2 text-sm text-slate-500">{t('waste.newDescription')}</p>
          <div className="mt-5 grid gap-4 sm:grid-cols-2">
            <Field label={t('waste.wasteType')} value={form.wasteType} onChange={value => updateForm('wasteType', value)} required />
            <Field label={t('waste.clearReason')} value={form.clearReason} onChange={value => updateForm('clearReason', value)} required />
            <Field label={t('waste.pickup')} value={form.pickupLocation} onChange={value => updateForm('pickupLocation', value)} required />
            <Field label={t('waste.site')} value={form.processingSite} onChange={value => updateForm('processingSite', value)} required />
            <Field label={t('waste.weight')} type="number" min="0.01" step="0.01" value={form.estimatedWeight} onChange={value => updateForm('estimatedWeight', value)} required />
            <label className="block text-sm font-medium text-slate-700"><span>{t('waste.vehicle')}<b className="ml-1 text-rose-500">*</b></span><select className="input mt-1.5" value={form.vehicleId} onChange={event => updateForm('vehicleId', event.target.value)} required><option value="">{t('waste.selectVehicle')}</option>{vehicles.map(vehicle => <option key={vehicle.id} value={vehicle.id}>{vehicle.plate_no} · {vehicle.driver_name}</option>)}</select></label>
            <Field label={t('waste.orderSubject')} value={form.orderSubject} onChange={value => updateForm('orderSubject', value)} required />
            <Field label={t('waste.subject')} value={form.subjectName} onChange={value => updateForm('subjectName', value)} required />
            <Field label={t('waste.contactName')} value={form.contactName} onChange={value => updateForm('contactName', value)} required />
            <Field label={t('waste.contactPhone')} value={form.contactPhone} onChange={value => updateForm('contactPhone', value)} required />
            <div className="sm:col-span-2"><Field label={t('waste.route')} value={form.routeDescription} onChange={value => updateForm('routeDescription', value)} /></div>
            <label className="sm:col-span-2"><span className="text-sm font-medium text-slate-700">{t('waste.photos')}<b className="ml-1 text-rose-500">*</b></span><div className="mt-1.5 flex cursor-pointer items-center gap-3 rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-3 transition hover:border-blue-400 hover:bg-blue-50/40"><ImagePlus className="h-5 w-5 text-blue-500" /><div className="min-w-0 flex-1 text-sm text-slate-600">{photoFiles.length ? t('waste.photosSelected', { count: photoFiles.length }) : t('waste.photoHint')}<div className="mt-1 text-xs text-slate-400">{t('waste.photoFormats')}</div></div><input className="sr-only" type="file" accept="image/*" multiple onChange={event => setPhotoFiles(Array.from(event.target.files || []))} /></div></label>
          </div>
          <button disabled={!!busy || !vehicles.length} className="mt-5 w-full rounded-xl bg-blue-600 px-4 py-3 text-sm font-semibold text-white shadow-lg shadow-blue-600/20 transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50">{busy === 'create' && <Loader2 className="mr-2 inline h-4 w-4 animate-spin" />}{t('waste.submitApplication')}<ArrowRight className="ml-2 inline h-4 w-4" /></button>
        </form>

        <div className="space-y-6">
          <form onSubmit={verifyGate} className="surface p-5 sm:p-6">
            <div className="eyebrow">{t('waste.gateEyebrow')}</div><h2 className="mt-1 text-xl font-semibold text-slate-900">{t('waste.gateTitle')}</h2><p className="mt-2 text-sm text-slate-500">{t('waste.gateDescription')}</p>
            <div className="mt-5 grid gap-4 sm:grid-cols-2"><Field label={t('waste.authorizationCode')} value={gateCode} onChange={setGateCode} placeholder="000000" required /><Field label={t('waste.plateNo')} value={gatePlate} onChange={setGatePlate} placeholder="粤A·7K52Q" required /></div>
            <button disabled={!!busy} className="mt-4 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm font-semibold text-slate-800 transition hover:border-blue-300 hover:text-blue-700 disabled:opacity-50">{busy === 'gate' && <Loader2 className="mr-2 inline h-4 w-4 animate-spin" />}{t('waste.verifyGate')}</button>
            {gateResult && <div className={`mt-4 rounded-2xl p-4 ${gateResult.allowed ? 'bg-emerald-50 text-emerald-800' : 'bg-rose-50 text-rose-800'}`}><div className="flex items-center gap-2 font-semibold">{gateResult.allowed ? <CheckCircle2 className="h-5 w-5" /> : <XCircle className="h-5 w-5" />}{gateResult.message}</div>{gateResult.application && <div className="mt-2 text-sm">{gateResult.application.application_no} · {gateResult.application.plate_no}</div>}</div>}
          </form>

          <form onSubmit={addWeighing} className="surface p-5 sm:p-6">
            <div className="eyebrow">{t('waste.weighingEyebrow')}</div><h2 className="mt-1 text-xl font-semibold text-slate-900">{t('waste.weighingTitle')}</h2><p className="mt-2 text-sm text-slate-500">{selected ? t('waste.weighingFor', { value: selected.application_no }) : t('waste.selectApprovedHint')}</p>
            <div className="mt-5 grid gap-4 sm:grid-cols-2"><label className="block text-sm font-medium text-slate-700"><span>{t('waste.eventType')}</span><select className="input mt-1.5" value={weighing.eventType} onChange={event => setWeighing(current => ({ ...current, eventType: event.target.value }))}><option value="INBOUND">{t('waste.inbound')}</option><option value="OUTBOUND">{t('waste.outbound')}</option></select></label><label className="block text-sm font-medium text-slate-700"><span>{t('waste.weighingApplication')}</span><select className="input mt-1.5" value={selected?.id || ''} onChange={event => setSelectedId(event.target.value)}><option value="">{t('waste.selectApprovedHint')}</option>{approvedApplications.map(item => <option key={item.id} value={item.id}>{item.application_no} · {item.plate_no}</option>)}</select></label><Field label={t('waste.grossWeight')} type="number" min="0" step="0.01" value={weighing.grossWeight} onChange={value => setWeighing(current => ({ ...current, grossWeight: value }))} required /><Field label={t('waste.tareWeight')} type="number" min="0" step="0.01" value={weighing.tareWeight} onChange={value => setWeighing(current => ({ ...current, tareWeight: value }))} required /><Field label={t('waste.weighbridge')} value={weighing.weighbridgeNo} onChange={value => setWeighing(current => ({ ...current, weighbridgeNo: value }))} /><Field label={t('waste.operator')} value={weighing.operatorName} onChange={value => setWeighing(current => ({ ...current, operatorName: value }))} /></div>
            <button disabled={!!busy || !selected || selected.status !== 'APPROVED'} className="mt-4 w-full rounded-xl bg-slate-950 px-4 py-3 text-sm font-semibold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40">{busy === 'weighing' && <Loader2 className="mr-2 inline h-4 w-4 animate-spin" />}{t('waste.saveWeighing')}</button>
          </form>
        </div>
      </section>
    </div>
  )
}

function Kpi({ icon: Icon, label, value, tone }: { icon: any; label: string; value: unknown; tone: string }) {
  const tones: Record<string, string> = { blue: 'bg-blue-50 text-blue-600', amber: 'bg-amber-50 text-amber-600', emerald: 'bg-emerald-50 text-emerald-600', cyan: 'bg-cyan-50 text-cyan-600', violet: 'bg-violet-50 text-violet-600', slate: 'bg-slate-100 text-slate-600' }
  return <div className="surface p-4"><div className="flex items-center gap-3"><div className={`flex h-10 w-10 items-center justify-center rounded-xl ${tones[tone]}`}><Icon className="h-5 w-5" /></div><div className="min-w-0"><div className="text-xl font-bold text-slate-900">{value == null ? '—' : String(value)}</div><div className="truncate text-xs text-slate-400">{label}</div></div></div></div>
}

function Detail({ label, value }: { label: string; value: unknown }) {
  return <div className="rounded-xl bg-slate-50 px-3 py-2.5"><div className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">{label}</div><div className="mt-1 break-words text-sm font-medium text-slate-700">{value == null || value === '' ? '—' : String(value)}</div></div>
}

function Field({ label, value, onChange, type = 'text', min, step, placeholder, required }: { label: string; value: string; onChange: (value: string) => void; type?: string; min?: string; step?: string; placeholder?: string; required?: boolean }) {
  return <label className="block text-sm font-medium text-slate-700"><span>{label}{required && <b className="ml-1 text-rose-500">*</b>}</span><input className="input mt-1.5" type={type} min={min} step={step} value={value} onChange={event => onChange(event.target.value)} placeholder={placeholder} required={required} /></label>
}
