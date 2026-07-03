import { useEffect, useMemo, useState } from 'react'
import { modelsApi } from '../api/client'
import { CheckCircle2, KeyRound, Play, Plus, ServerCog, Trash2, XCircle } from 'lucide-react'

const TYPE_LABELS: Record<string, string> = {
  CHAT: 'Large Language',
  EMBEDDING: 'Vector',
  RERANKING: 'Reranking',
}

const DEFAULT_PROTOCOLS: Record<string, string[]> = {
  CHAT: ['SPRING_AI_CHAT', 'OPENAI_CHAT_COMPLETIONS', 'OLLAMA_CHAT', 'ANTHROPIC_MESSAGES', 'GOOGLE_GEMINI_GENERATE_CONTENT'],
  EMBEDDING: ['SPRING_AI_EMBEDDING', 'OPENAI_EMBEDDINGS', 'OLLAMA_EMBEDDINGS', 'GOOGLE_GEMINI_EMBEDDING'],
  RERANKING: ['COHERE_RERANK', 'JINA_RERANK'],
}

const CUSTOM_PROVIDER = { id: 'custom', name: 'Custom', source: 'CUSTOM', baseUrl: '', models: [] as any[] }

export default function Models() {
  const [models, setModels] = useState<any[]>([])
  const [capabilities, setCapabilities] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [error, setError] = useState('')
  const [testResult, setTestResult] = useState<any>(null)

  const [name, setName] = useState('')
  const [type, setType] = useState('CHAT')
  const [providerId, setProviderId] = useState('openai')
  const [provider, setProvider] = useState('openai')
  const [source, setSource] = useState('OFFICIAL')
  const [protocol, setProtocol] = useState('OPENAI_CHAT_COMPLETIONS')
  const [modelName, setModelName] = useState('gpt-4o-mini')
  const [baseUrl, setBaseUrl] = useState('https://api.openai.com/v1')
  const [apiKey, setApiKey] = useState('')
  const [maxInputLength, setMaxInputLength] = useState(128000)
  const [maxOutputLength, setMaxOutputLength] = useState(4096)
  const [testInput, setTestInput] = useState('Hello X-NLP')

  const providers = useMemo(() => {
    const fromServer = capabilities?.providers || []
    return fromServer.length ? fromServer : [CUSTOM_PROVIDER]
  }, [capabilities])

  const selectedProvider = useMemo(() => {
    return providers.find((p: any) => p.id === providerId) || CUSTOM_PROVIDER
  }, [providerId, providers])

  const providerModels = useMemo(() => {
    return (selectedProvider.models || []).filter((m: any) => m.type === type)
  }, [selectedProvider, type])

  const protocols = useMemo(() => {
    return capabilities?.protocolsByType?.[type] || DEFAULT_PROTOCOLS[type] || []
  }, [capabilities, type])

  const isCustom = providerId === 'custom'

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
    applyProviderModel(selectedProvider, providerModels[0], type)
  }, [selectedProvider, providerModels, type])

  const applyProviderModel = (providerPreset: any, modelPreset: any, targetType: string) => {
    setProvider(providerPreset.id)
    setSource(providerPreset.source || 'CUSTOM')
    if (providerPreset.id !== 'custom') setBaseUrl(providerPreset.baseUrl || '')

    if (modelPreset) {
      setModelName(modelPreset.name)
      setProtocol(modelPreset.protocol)
      setMaxInputLength(modelPreset.maxInputLength || 4096)
      setMaxOutputLength(modelPreset.maxOutputLength || 2048)
      if (!name) setName(`${providerPreset.id}-${modelPreset.name}`.replace(/\./g, '-'))
    } else if (providerPreset.id === 'custom') {
      const nextProtocol = protocols[0] || DEFAULT_PROTOCOLS[targetType]?.[0]
      if (nextProtocol) setProtocol(nextProtocol)
      setModelName('')
      setMaxInputLength(4096)
      setMaxOutputLength(targetType === 'EMBEDDING' || targetType === 'RERANKING' ? 0 : 2048)
    } else {
      const nextProtocol = protocols[0] || DEFAULT_PROTOCOLS[targetType]?.[0]
      if (nextProtocol) setProtocol(nextProtocol)
      setModelName('')
      setMaxInputLength(4096)
      setMaxOutputLength(targetType === 'EMBEDDING' || targetType === 'RERANKING' ? 0 : 2048)
    }
  }

  const reset = () => {
    setName(''); setType('CHAT'); setProviderId('openai'); setProvider('openai'); setSource('OFFICIAL')
    setProtocol('OPENAI_CHAT_COMPLETIONS'); setModelName('gpt-4o-mini'); setBaseUrl('https://api.openai.com/v1')
    setApiKey(''); setMaxInputLength(128000); setMaxOutputLength(4096)
  }

  const save = async () => {
    setError('')
    try {
      await modelsApi.create({
        name: name.trim(),
        type,
        source,
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
      <div className="flex flex-col gap-3 mb-6 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-gray-900">Models</h1>
          <p className="text-sm text-gray-500 mt-1">Choose a provider, select an official model, or define a custom standard-compatible endpoint.</p>
        </div>
        <button onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700">
          <Plus className="w-4 h-4" /> New Model
        </button>
      </div>

      {error && <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      {showForm && (
        <div className="bg-white border rounded-lg p-5 mb-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold text-gray-700">Model Profile</h2>
            <SourceBadge source={source} />
          </div>

          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2 xl:grid-cols-3">
            <Field label="Provider">
              <select value={providerId} onChange={e => setProviderId(e.target.value)} className="input">
                {providers.map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </Field>
            <Field label="Type">
              <select value={type} onChange={e => setType(e.target.value)} className="input">
                {Object.keys(TYPE_LABELS).map(t => <option key={t} value={t}>{TYPE_LABELS[t]}</option>)}
              </select>
            </Field>
            <Field label="Model">
              {isCustom ? (
                <input value={modelName} onChange={e => setModelName(e.target.value)} className="input" placeholder="custom-model-name" />
              ) : (
                <select value={modelName} onChange={e => {
                  const model = providerModels.find((m: any) => m.name === e.target.value)
                  if (model) applyProviderModel(selectedProvider, model, type)
                }} className="input">
                  {providerModels.map((m: any) => <option key={m.name} value={m.name}>{m.name}</option>)}
                </select>
              )}
            </Field>
          </div>

          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2 xl:grid-cols-3">
            <Field label="Profile Name"><input value={name} onChange={e => setName(e.target.value)} className="input" placeholder="prod-chat" /></Field>
            <Field label="Provider Id"><input value={provider} onChange={e => setProvider(e.target.value)} className="input" disabled={!isCustom} /></Field>
            <Field label="Protocol">
              <select value={protocol} onChange={e => setProtocol(e.target.value)} className="input" disabled={!isCustom}>
                {protocols.map((p: string) => <option key={p} value={p}>{p}</option>)}
              </select>
            </Field>
          </div>

          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2 xl:grid-cols-3">
            <Field label="Base URL"><input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} className="input" disabled={!isCustom} placeholder="https://api.openai.com/v1" /></Field>
            <Field label="API Key"><input value={apiKey} onChange={e => setApiKey(e.target.value)} className="input" type="password" placeholder="Stored server-side" /></Field>
            <Field label="Source">
              <select value={source} onChange={e => setSource(e.target.value)} className="input" disabled={!isCustom}>
                <option value="OFFICIAL">OFFICIAL</option>
                <option value="CUSTOM">CUSTOM</option>
              </select>
            </Field>
          </div>

          <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2 xl:grid-cols-3">
            <Field label="Max Input"><input value={maxInputLength} onChange={e => setMaxInputLength(Number(e.target.value))} className="input" type="number" /></Field>
            <Field label="Max Output"><input value={maxOutputLength} onChange={e => setMaxOutputLength(Number(e.target.value))} className="input" type="number" /></Field>
            <div className="flex items-end text-xs text-gray-400">Official presets fill protocol, base URL, and limits automatically.</div>
          </div>

          <div className="flex flex-wrap gap-3">
            <button onClick={save} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">Save</button>
            <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">Cancel</button>
          </div>
        </div>
      )}

      <div className="bg-white border rounded-lg overflow-x-auto">
        {loading ? <p className="p-4 text-sm text-gray-400">Loading...</p> : (
          <table className="w-full min-w-[900px] text-sm">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-4 py-3 font-medium text-gray-500">Name</th>
                <th className="px-4 py-3 font-medium text-gray-500">Source</th>
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
                  <td className="px-4 py-3 font-medium text-gray-900 break-words">{m.name}</td>
                  <td className="px-4 py-3"><SourceBadge source={m.source || 'CUSTOM'} /></td>
                  <td className="px-4 py-3"><TypeBadge type={m.type} /></td>
                  <td className="px-4 py-3 text-gray-600 font-mono text-xs break-words">{m.protocol}</td>
                  <td className="px-4 py-3 text-gray-600 break-words">{m.provider} / {m.modelName || '-'}</td>
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
        <textarea value={testInput} onChange={e => setTestInput(e.target.value)} rows={3} className="w-full min-w-0 border rounded-md px-3 py-2 text-sm" />
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

function SourceBadge({ source }: { source: string }) {
  const official = source === 'OFFICIAL'
  return <span className={`text-xs px-2 py-0.5 rounded-full ${official ? 'bg-indigo-50 text-indigo-700' : 'bg-gray-100 text-gray-600'}`}>{source || 'CUSTOM'}</span>
}

function Status({ value }: { value: string }) {
  const loaded = value === 'loaded'
  return <span className={`text-xs px-2 py-0.5 rounded-full ${loaded ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{value || 'configured'}</span>
}
