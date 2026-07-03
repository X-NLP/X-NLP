import { useEffect, useState } from 'react'
import { datasetsApi } from '../api/client'
import { Plus, Trash2, Download, ChevronLeft, ChevronRight, Upload, Database } from 'lucide-react'

const TASK_TYPES = ['TEXT_CLASSIFICATION', 'SENTIMENT_ANALYSIS', 'SUMMARIZATION',
  'NAMED_ENTITY_RECOGNITION', 'QUESTION_ANSWERING', 'TRANSLATION']

export default function Datasets() {
  const [datasets, setDatasets] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [viewing, setViewing] = useState<any>(null)
  const [entries, setEntries] = useState<any>(null)

  // New dataset form
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [taskType, setTaskType] = useState('TEXT_CLASSIFICATION')
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
    if (!name.trim()) { setFormError('Name is required.'); return }
    let entries: any[]
    try {
      entries = JSON.parse(jsonText)
      if (!Array.isArray(entries)) throw new Error('JSON must be an array of {input, expectedOutput} objects.')
    } catch {
      setFormError('Invalid JSON. Provide an array of objects with "input" and "expectedOutput".')
      return
    }
    try {
      await datasetsApi.create({ name: name.trim(), description: desc.trim(), taskType, entries })
      setName(''); setDesc(''); setJsonText(''); setShowForm(false)
      await load()
    } catch (e: any) {
      setFormError(e.message)
    }
  }

  const handleDelete = async (id: string) => {
    if (!confirm('Delete this dataset?')) return
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
        <h1 className="text-xl font-semibold text-gray-900">Datasets</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors"
        >
          <Plus className="w-4 h-4" /> New Dataset
        </button>
      </div>

      {showForm && (
        <div className="bg-white border rounded-lg p-5 mb-6">
          <h2 className="text-sm font-semibold text-gray-700 mb-4">Create New Dataset</h2>
          {formError && <p className="text-sm text-red-600 mb-3">{formError}</p>}
          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">Name</label>
              <input value={name} onChange={e => setName(e.target.value)}
                className="w-full min-w-0 border rounded-md px-3 py-2 text-sm" placeholder="e.g. sentiment-test-v1" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">Task Type</label>
              <select value={taskType} onChange={e => setTaskType(e.target.value)}
                className="w-full min-w-0 border rounded-md px-3 py-2 text-sm">
                {TASK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
          </div>
          <div className="mb-4">
            <label className="block text-xs font-medium text-gray-500 mb-1">Description</label>
            <input value={desc} onChange={e => setDesc(e.target.value)}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm" placeholder="Optional description" />
          </div>
          <div className="mb-4">
            <label className="block text-xs font-medium text-gray-500 mb-1">
              Entries (JSON array)
            </label>
            <textarea value={jsonText} onChange={e => setJsonText(e.target.value)}
              rows={8}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm font-mono"
              placeholder='[{"input": "...", "expectedOutput": "positive"}]' />
            <p className="text-xs text-gray-400 mt-1">
              Each entry: {'{'} "input": "text...", "expectedOutput": "label..." {'}'}
            </p>
          </div>
          <div className="flex flex-wrap gap-3">
            <button onClick={handleCreate}
              className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">
              <Upload className="w-4 h-4 inline mr-1" /> Create
            </button>
            <button onClick={() => setShowForm(false)}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">Cancel</button>
          </div>
        </div>
      )}

      {loading ? (
        <p className="text-gray-500 text-sm">Loading...</p>
      ) : datasets.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <Database className="w-10 h-10 mx-auto mb-3 text-gray-300" />
          <p className="text-sm">No datasets yet. Create one to start evaluating models.</p>
        </div>
      ) : (
        <div className="bg-white border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[720px] text-sm">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-4 py-3 font-medium text-gray-500">Name</th>
                <th className="px-4 py-3 font-medium text-gray-500">Task</th>
                <th className="px-4 py-3 font-medium text-gray-500">Entries</th>
                <th className="px-4 py-3 font-medium text-gray-500">Created</th>
                <th className="px-4 py-3 font-medium text-gray-500 w-32">Actions</th>
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
                        className="p-1.5 text-gray-400 hover:text-gray-600 rounded" title="Export JSON">
                        <Download className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleDelete(ds.id)}
                        className="p-1.5 text-gray-400 hover:text-red-600 rounded" title="Delete">
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
            <p className="text-sm text-gray-500 mb-4">Task: {viewing.taskType} &middot; {viewing.entryCount} entries</p>
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
              <p className="text-sm text-gray-400">Loading entries...</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
