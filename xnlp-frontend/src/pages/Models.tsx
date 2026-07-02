import { useEffect, useMemo, useState } from 'react'
import { modelsApi } from '../api/client'
import { CheckCircle2, KeyRound, Play, Plus, ServerCog, Trash2, XCircle } from 'lucide-react'

const TYPE_LABELS: Record<string, string> = {
  CHAT: 'Large Language',
  EMBEDDING: 'Vector',
  RERANKING: 'Reranking',
}

const DEFAULT_PROTOCOLS: Record<string, string[]> = {
  CHAT: ['SPRING_AI_CHAT', 'OPENAI_CHAT_COMPLETIONS', 'OLLAMA_CHAT'],
  EMBEDDING: ['SPRING_AI_EMBEDDING', 'OPENAI_EMBEDDINGS', 'OLLAMA_EMBEDDINGS'],
  RERANKING: ['COHERE_RERANK', 'JINA_RERANK'],
}

export default function Models() {
  const [models, setModels] = useState<any[]>([])
  const [capabilities, setCapabilities] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [error, setError] = useState('')
  const [testResult, setTestResult] = useState<any>(null)

  const [name, setName] = useState('')
  const [type, setType] = useState('CHAT')
  const [protocol, setProtocol] = useState('SPRING_AI_CHAT')
  const [provider, setProvider] = useState('spring-ai')
  const [modelName, setModelName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [maxInputLength, setMaxInputLength] = useState(4096)
  const [maxOutputLength, setMaxOutputLength] = useState(2048)
  const [testInput, setTestInput] = useState('Hello X-NLP')

  const protocols = useMemo(() => {
    return capabilities?.protocolsByType?.[type] || DEFAULT_PROTOCOLS[type] || []
  }, [capabilities, type])

  const load = async () => {
    try {
      const [list, caps] = await Promise.all([modelsApi.list(), modelsApi.capabilities()])
      setModels(list)
      setCapabilities(caps)
    } catch (e: any) {
      setError(e.message)
    }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  useEffect(() => {
    const next = protocols[0]
    if (next && !protocols.includes(protocol)) setProtocol(next)
  }, [protocol, protocols])

  useEffect(() => {
    if (protocol.startsWith('OPENAI')) setProvider('openai')
    else if (protocol.startsWith('OLLAMA')) setProvider('ollama')
    else if (protocol.startsWith('COHERE')) setProvider('cohere')
    else if (protocol.startsWith('JINA')) setProvider('jina')
    else setProvider('spring-ai')
  }, [protocol])

  const reset = () => {
    setName(''); setType('CHAT'); setProtocol('SPRING_AI_CHAT'); setProvider('spring-ai')
    setModelName(''); setBaseUrl(''); setApiKey(''); setMaxInputLength(4096); setMaxOutputLength(2048)
  }

  const save = async () => {
    setError('')
    try {
      await modelsApi.create({
        name: name.trim(),
        type,
        protocol,
        provider: provider.trim(),
        modelName: modelName.trim(),
        model_path: modelName.trim(),
        baseUrl: baseUrl.trim() || undefined,
        apiKey: apiKey.trim() || undefined,
        backend: provider.trim(),
        maxInputLength,
        maxOutputLength,
      })
      reset(); setShowForm(false); await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  const activate = async (model: any) => {
    setError(''); setTestResult(null)
    try {
      await modelsApi.activate(model.name)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  const test = async (model: any) => {
    setError(''); setTestResult(null)
    try {
      const result = await modelsApi.test(model.name, { input: testInput })
      setTestResult({ model: model.name, result })
    } catch (e: any) {
      setError(e.message)
    }
  }

  const remove = async (model: any) => {
    if (!confirm(`Delete model profile ${model.name}?`)) return
    await modelsApi.delete(model.name)
    await load()
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Models</h1>
          <p className="text-sm text-gray-500 mt-1">Manage standard model profiles for LLM, vector, and reranking use cases.</p>
        </div>
        <button onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700">
          <Plus className="w-4 h-4" /> New Model
        </button>
      </div>

      {error && <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      {showForm && (
        <div className="bg-white border rounded-lg p-5 mb-6">
          <h2 className="text-sm font-semibold text-gray-700 mb-4">Model Profile</h2>
          <div className="grid grid-cols-3 gap-4 mb-4">
            <Field label="Name"><input value={name} onChange={e => setName(e.target.value)} className="input" placeholder="prod-chat" /></Field>
            <Field label="Type">
              <select value={type} onChange={e => setType(e.target.value)} className="input">
                {Object.keys(TYPE_LABELS).map(t => <option key={t} value={t}>{TYPE_LABELS[t]}</option>)}
              </select>
            </Field>
            <Field label="Protocol">
              <select value={protocol} onChange={e => setProtocol(e.target.value)} className="input">
                {protocols.map((p: string) => <option key={p} value={p}>{p}</option>)}
              </select>
            </Field>
          </div>
          <div className="grid grid-cols-3 gap-4 mb-4">
            <Field label="Provider"><input value={provider} onChange={e => setProvider(e.target.value)} className="input" /></Field>
            <Field label="Provider Model Name"><input value={modelName} onChange={e => setModelName(e.target.value)} className="input" placeholder="gpt-4o-mini" /></Field>
            <Field label="Base URL"><input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} className="input" placeholder="https://api.openai.com/v1" /></Field>
          </div>
          <div className="grid grid-cols-3 gap-4 mb-4">
            <Field label="API Key"><input value={apiKey} onChange={e => setApiKey(e.target.value)} className="input" type="password" placeholder="Stored server-side" /></Field>
            <Field label="Max Input"><input value={maxInputLength} onChange={e => setMaxInputLength(Number(e.target.value))} className="input" type="number" /></Field>
            <Field label="Max Output"><input value={maxOutputLength} onChange={e => setMaxOutputLength(Number(e.target.value))} className="input" type="number" /></Field>
          </div>
          <div className="flex gap-3">
            <button onClick={save} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">Save</button>
            <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">Cancel</button>
          </div>
        </div>
      )}

      <div className="bg-white border rounded-lg overflow-hidden">
        {loading ? <p className="p-4 text-sm text-gray-400">Loading...</p> : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-4 py-3 font-medium text-gray-500">Name</th>
                <th className="px-4 py-3 font-medium text-gray-500">Type</th>
                <th className="px-4 py-3 font-medium text-gray-500">Protocol</th>
                <th className="px-4 py-3 font-medium text-gray-500">Provider Model</th>
                <th className="px-4 py-3 font-medium text-gray-500">Auth</th>
                <th className="px-4 py-3 font-medium text-gray-500">Status</th>
                <th className="px-4 py-3 font-medium text-gray-500 w-40">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {models.map((m: any) => (
                <tr key={m.name} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">{m.name}</td>
                  <td className="px-4 py-3"><TypeBadge type={m.type} /></td>
                  <td className="px-4 py-3 text-gray-600 font-mono text-xs">{m.protocol}</td>
                  <td className="px-4 py-3 text-gray-600">{m.modelName || '-'}</td>
                  <td className="px-4 py-3">{m.apiKeySet ? <KeyRound className="w-4 h-4 text-emerald-600" /> : <XCircle className="w-4 h-4 text-gray-300" />}</td>
                  <td className="px-4 py-3"><Status value={m.status} /></td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button onClick={() => activate(m)} title="Activate" className="icon-btn"><CheckCircle2 className="w-4 h-4" /></button>
                      <button onClick={() => test(m)} title="Test" className="icon-btn"><Play className="w-4 h-4" /></button>
                      <button onClick={() => remove(m)} title="Delete" className="icon-btn danger"><Trash2 className="w-4 h-4" /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="mt-6 bg-white border rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2"><ServerCog className="w-4 h-4" /> Test Input</h2>
        <textarea value={testInput} onChange={e => setTestInput(e.target.value)} rows={3} className="w-full border rounded-md px-3 py-2 text-sm" />
        {testResult && <pre className="mt-4 bg-gray-950 text-gray-100 rounded-lg p-4 text-xs overflow-auto">{JSON.stringify(testResult, null, 2)}</pre>}
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: any }) {
  return <label className="block"><span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>{children}</label>
}

function TypeBadge({ type }: { type: string }) {
  const styles: Record<string, string> = {
    CHAT: 'bg-blue-50 text-blue-700',
    EMBEDDING: 'bg-emerald-50 text-emerald-700',
    RERANKING: 'bg-amber-50 text-amber-700',
  }
  return <span className={`text-xs px-2 py-0.5 rounded-full ${styles[type] || 'bg-gray-100 text-gray-600'}`}>{TYPE_LABELS[type] || type}</span>
}

function Status({ value }: { value: string }) {
  const loaded = value === 'loaded'
  return <span className={`text-xs px-2 py-0.5 rounded-full ${loaded ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{value || 'configured'}</span>
}
