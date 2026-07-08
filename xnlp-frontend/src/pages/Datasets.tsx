import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { datasetsApi } from '../api/client'
import { Plus, Trash2, Download, Upload, Database } from 'lucide-react'

const TASK_TYPES = ['TEXT_CLASSIFICATION', 'SENTIMENT_ANALYSIS', 'SUMMARIZATION',
  'NAMED_ENTITY_RECOGNITION', 'QUESTION_ANSWERING', 'TRANSLATION']

type InputMode = 'quick' | 'json'

type QuickEntry = {
  input: string
  expectedOutput: string
}

const emptyQuickEntry = (): QuickEntry => ({ input: '', expectedOutput: '' })

export default function Datasets() {
  const { t } = useTranslation()
  const [datasets, setDatasets] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [viewing, setViewing] = useState<any>(null)
  const [entries, setEntries] = useState<any>(null)

  // New dataset form
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [taskType, setTaskType] = useState('TEXT_CLASSIFICATION')
  const [inputMode, setInputMode] = useState<InputMode>('quick')
  const [quickEntries, setQuickEntries] = useState<QuickEntry[]>([emptyQuickEntry()])
  const [jsonText, setJsonText] = useState('')
  const [formError, setFormError] = useState('')

  const load = async () => {
    try {
      const list = await datasetsApi.list()
      setDatasets(list)
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  const handleCreate = async () => {
    setFormError('')
    if (!name.trim()) { setFormError(t('datasets.nameRequired')); return }
    let entries: any[]
    if (inputMode === 'quick') {
      entries = quickEntries
        .map(entry => ({ input: entry.input.trim(), expectedOutput: entry.expectedOutput.trim() }))
        .filter(entry => entry.input || entry.expectedOutput)
      if (entries.length === 0) { setFormError(t('datasets.quickEntriesRequired')); return }
      if (entries.some(entry => !entry.input || !entry.expectedOutput)) { setFormError(t('datasets.quickEntriesComplete')); return }
    } else {
      try {
        entries = JSON.parse(jsonText)
        if (!Array.isArray(entries)) throw new Error(t('datasets.jsonArrayRequired'))
      } catch {
        setFormError(t('datasets.invalidJson'))
        return
      }
    }
    try {
      await datasetsApi.create({ name: name.trim(), description: desc.trim(), taskType, entries })
      setName(''); setDesc(''); setJsonText(''); setQuickEntries([emptyQuickEntry()]); setInputMode('quick'); setShowForm(false)
      await load()
    } catch (e: any) {
      setFormError(e.message)
    }
  }

  const updateQuickEntry = (index: number, field: keyof QuickEntry, value: string) => {
    setQuickEntries(entries => entries.map((entry, i) => i === index ? { ...entry, [field]: value } : entry))
  }

  const addQuickEntry = () => setQuickEntries(entries => [...entries, emptyQuickEntry()])

  const removeQuickEntry = (index: number) => {
    setQuickEntries(entries => entries.length === 1 ? [emptyQuickEntry()] : entries.filter((_, i) => i !== index))
  }

  const handleDelete = async (id: string) => {
    if (!confirm(t('datasets.deleteConfirm'))) return
    await datasetsApi.delete(id)
    await load()
  }

  const handleView = async (ds: any) => {
    setViewing(ds)
    try {
      const data = await datasetsApi.entries(ds.id)
      setEntries(data)
    } catch { setEntries(null) }
  }

  const handleExport = async (ds: any) => {
    try {
      const json = await datasetsApi.exportJson(ds.id)
      const blob = new Blob([json], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = `${ds.name}.json`; a.click()
      URL.revokeObjectURL(url)
    } catch { /* ignore */ }
  }

  return (
    <div>
      <div className="flex flex-col gap-3 mb-6 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-xl font-semibold text-gray-900">{t('datasets.title')}</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors"
        >
          <Plus className="w-4 h-4" /> {t('datasets.newDataset')}
        </button>
      </div>

      {showForm && (
        <div className="bg-white border rounded-lg p-5 mb-6">
          <h2 className="text-sm font-semibold text-gray-700 mb-4">{t('datasets.createTitle')}</h2>
          {formError && <p className="text-sm text-red-600 mb-3">{formError}</p>}
          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">{t('datasets.name')}</label>
              <input value={name} onChange={e => setName(e.target.value)}
                className="w-full min-w-0 border rounded-md px-3 py-2 text-sm" placeholder="e.g. sentiment-test-v1" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">{t('datasets.taskType')}</label>
              <select value={taskType} onChange={e => setTaskType(e.target.value)}
                className="w-full min-w-0 border rounded-md px-3 py-2 text-sm">
                {TASK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
          </div>
          <div className="mb-4">
            <label className="block text-xs font-medium text-gray-500 mb-1">{t('datasets.description')}</label>
            <input value={desc} onChange={e => setDesc(e.target.value)}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm" placeholder={t('datasets.optionalDescription')} />
          </div>
          <div className="mb-4">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setInputMode('quick')}
                className={`rounded-md px-3 py-1.5 text-xs font-medium ${inputMode === 'quick' ? 'bg-gray-900 text-white' : 'border text-gray-600 hover:bg-gray-50'}`}
              >
                {t('datasets.quickMode')}
              </button>
              <button
                type="button"
                onClick={() => setInputMode('json')}
                className={`rounded-md px-3 py-1.5 text-xs font-medium ${inputMode === 'json' ? 'bg-gray-900 text-white' : 'border text-gray-600 hover:bg-gray-50'}`}
              >
                {t('datasets.jsonMode')}
              </button>
            </div>

            {inputMode === 'quick' ? (
              <div className="space-y-3">
                {quickEntries.map((entry, index) => (
                  <div key={index} className="grid grid-cols-1 gap-3 rounded-lg border bg-gray-50 p-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,280px)_auto]">
                    <label className="block min-w-0">
                      <span className="mb-1 block text-xs font-medium text-gray-500">{t('datasets.inputText')}</span>
                      <textarea
                        value={entry.input}
                        onChange={event => updateQuickEntry(index, 'input', event.target.value)}
                        rows={2}
                        className="w-full min-w-0 rounded-md border bg-white px-3 py-2 text-sm"
                        placeholder={t('datasets.inputPlaceholder')}
                      />
                    </label>
                    <label className="block min-w-0">
                      <span className="mb-1 block text-xs font-medium text-gray-500">{t('datasets.expectedOutput')}</span>
                      <textarea
                        value={entry.expectedOutput}
                        onChange={event => updateQuickEntry(index, 'expectedOutput', event.target.value)}
                        rows={2}
                        className="w-full min-w-0 rounded-md border bg-white px-3 py-2 text-sm"
                        placeholder={t('datasets.expectedPlaceholder')}
                      />
                    </label>
                    <button
                      type="button"
                      onClick={() => removeQuickEntry(index)}
                      className="self-end rounded-md border px-3 py-2 text-sm text-gray-500 hover:border-red-200 hover:bg-red-50 hover:text-red-600"
                      title={t('datasets.removeEntry')}
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                ))}
                <button type="button" onClick={addQuickEntry} className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm text-gray-600 hover:bg-gray-50">
                  <Plus className="h-4 w-4" /> {t('datasets.addEntry')}
                </button>
              </div>
            ) : (
              <div>
                <label className="block text-xs font-medium text-gray-500 mb-1">
                  {t('datasets.entriesJson')}
                </label>
                <textarea value={jsonText} onChange={e => setJsonText(e.target.value)}
                  rows={8}
                  className="w-full min-w-0 border rounded-md px-3 py-2 text-sm font-mono"
                  placeholder='[{"input": "...", "expectedOutput": "positive"}]' />
                <p className="text-xs text-gray-400 mt-1">
                  {t('datasets.entryHint')}
                </p>
              </div>
            )}
          </div>
          <div className="flex flex-wrap gap-3">
            <button onClick={handleCreate}
              className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">
              <Upload className="w-4 h-4 inline mr-1" /> {t('datasets.create')}
            </button>
            <button onClick={() => setShowForm(false)}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">{t('common.cancel')}</button>
          </div>
        </div>
      )}

      {loading ? (
        <p className="text-gray-500 text-sm">{t('common.loading')}</p>
      ) : datasets.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <Database className="w-10 h-10 mx-auto mb-3 text-gray-300" />
          <p className="text-sm">{t('datasets.noDatasets')}</p>
        </div>
      ) : (
        <div className="bg-white border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[720px] text-sm">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-4 py-3 font-medium text-gray-500">{t('datasets.name')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('datasets.task')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('datasets.entries')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('datasets.created')}</th>
                <th className="px-4 py-3 font-medium text-gray-500 w-32">{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {datasets.map((ds: any) => (
                <tr key={ds.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3">
                    <button onClick={() => handleView(ds)} className="text-left text-blue-600 hover:text-blue-800 font-medium break-words">
                      {ds.name}
                    </button>
                    {ds.description && <p className="text-xs text-gray-400 mt-0.5 break-words">{ds.description}</p>}
                  </td>
                  <td className="px-4 py-3 text-gray-600">{ds.taskType}</td>
                  <td className="px-4 py-3 text-gray-600">{ds.entryCount}</td>
                  <td className="px-4 py-3 text-gray-400 text-xs">
                    {ds.createdAt ? new Date(ds.createdAt).toLocaleDateString() : '-'}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button onClick={() => handleExport(ds)}
                        className="p-1.5 text-gray-400 hover:text-gray-600 rounded" title={t('datasets.exportJson')}>
                        <Download className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleDelete(ds.id)}
                        className="p-1.5 text-gray-400 hover:text-red-600 rounded" title={t('models.delete')}>
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {viewing && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setViewing(null)}>
          <div className="bg-white rounded-xl shadow-2xl max-w-2xl w-full max-h-[80vh] overflow-y-auto p-4 sm:p-6"
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="min-w-0 text-lg font-semibold break-words">{viewing.name}</h3>
              <button onClick={() => setViewing(null)} className="text-gray-400 hover:text-gray-600 text-xl">&times;</button>
            </div>
            <p className="text-sm text-gray-500 mb-4">{t('datasets.task')}: {viewing.taskType} &middot; {viewing.entryCount} {t('datasets.entries')}</p>
            {entries ? (
              <div className="space-y-3">
                {entries.entries?.map((e: any, i: number) => (
                  <div key={e.id || i} className="border rounded-lg p-3 text-sm">
                    <p className="text-gray-900 break-words"><span className="text-gray-400 font-mono text-xs">input:</span> {e.input}</p>
                    <p className="text-green-700 mt-1 break-words"><span className="text-gray-400 font-mono text-xs">expected:</span> {e.expectedOutput}</p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-400">{t('datasets.loadingEntries')}</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
