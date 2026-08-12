import { FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Bot, Check, ChevronRight, Loader2, Send, Sparkles, WandSparkles } from 'lucide-react'
import { aiApi } from '../api/client'

type Message = { role: 'user' | 'assistant'; content: string }

const suggestions = [
  '设计一个文本分类评测方案，包含数据集、指标和失败分析。',
  '如何把分词、NER 和分类组合成可观测的 NLP pipeline？',
  '帮我检查一个 Spring AI 接入的生产化清单。',
]

export default function Assistant() {
  const { t } = useTranslation()
  const [message, setMessage] = useState('')
  const [context, setContext] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [running, setRunning] = useState(false)
  const [available, setAvailable] = useState<boolean | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    aiApi.status().then(value => setAvailable(Boolean(value.available))).catch(() => setAvailable(false))
  }, [])

  async function send(event?: FormEvent) {
    event?.preventDefault()
    if (!message.trim() || running) return
    const current = message.trim()
    setMessage('')
    setError('')
    setMessages(prev => [...prev, { role: 'user', content: current }])
    setRunning(true)
    try {
      const response = await aiApi.chat(current, context)
      setMessages(prev => [...prev, { role: 'assistant', content: response.content || t('assistant.emptyResponse') }])
      setAvailable(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : t('assistant.requestFailed'))
      setMessages(prev => [...prev, { role: 'assistant', content: t('assistant.requestFailed') }])
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="space-y-6">
      <section className="surface grid-pattern relative overflow-hidden px-6 py-7 sm:px-8">
        <div className="absolute -right-16 -top-20 h-56 w-56 rounded-full bg-blue-400/15 blur-3xl" />
        <div className="relative flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-2xl">
            <div className="eyebrow flex items-center gap-2"><Sparkles className="h-3.5 w-3.5" /> {t('assistant.eyebrow')}</div>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">{t('assistant.title')}</h1>
            <p className="mt-3 max-w-xl text-sm leading-6 text-slate-500">{t('assistant.subtitle')}</p>
          </div>
          <div className="flex shrink-0 items-center gap-2 rounded-full border border-white/80 bg-white/80 px-3 py-2 text-xs font-medium text-slate-600 backdrop-blur">
            <span className={`h-2 w-2 rounded-full ${available ? 'bg-emerald-400' : available === false ? 'bg-amber-400' : 'bg-slate-300 pulse-soft'}`} />
            {available ? t('assistant.connected') : available === false ? t('assistant.configureProvider') : t('common.loading')}
          </div>
        </div>
      </section>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <section className="surface flex min-h-[560px] flex-col overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-blue-50 text-blue-600"><Bot className="h-5 w-5" /></div>
              <div><h2 className="text-sm font-semibold text-slate-900">{t('assistant.chatTitle')}</h2><p className="text-xs text-slate-400">{t('assistant.chatSubtitle')}</p></div>
            </div>
            <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-500">Spring AI</span>
          </div>

          <div className="flex-1 space-y-4 overflow-y-auto bg-slate-50/60 p-5 sm:p-6">
            {messages.length === 0 && (
              <div className="flex min-h-[330px] flex-col items-center justify-center text-center">
                <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-violet-500 text-white shadow-lg shadow-blue-500/20"><WandSparkles className="h-6 w-6" /></div>
                <h3 className="mt-5 text-base font-semibold text-slate-900">{t('assistant.emptyTitle')}</h3>
                <p className="mt-2 max-w-md text-sm leading-6 text-slate-500">{t('assistant.emptyDescription')}</p>
                <div className="mt-6 flex max-w-xl flex-wrap justify-center gap-2">
                  {suggestions.map(item => <button key={item} onClick={() => setMessage(item)} className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-left text-xs text-slate-600 transition hover:border-blue-300 hover:text-blue-700">{item}</button>)}
                </div>
              </div>
            )}
            {messages.map((item, index) => (
              <div key={`${item.role}-${index}`} className={`flex ${item.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[88%] whitespace-pre-wrap rounded-2xl px-4 py-3 text-sm leading-6 ${item.role === 'user' ? 'rounded-br-md bg-slate-900 text-white' : 'rounded-bl-md border border-slate-200 bg-white text-slate-700 shadow-sm'}`}>{item.content}</div>
              </div>
            ))}
            {running && <div className="flex justify-start"><div className="flex items-center gap-2 rounded-2xl rounded-bl-md border border-slate-200 bg-white px-4 py-3 text-sm text-slate-500 shadow-sm"><Loader2 className="h-4 w-4 animate-spin text-blue-500" /> {t('assistant.thinking')}</div></div>}
          </div>

          <form onSubmit={send} className="border-t border-slate-100 bg-white p-4 sm:p-5">
            {error && <div className="mb-3 rounded-xl bg-red-50 px-3 py-2 text-xs text-red-600">{error}</div>}
            <div className="flex items-end gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-2 focus-within:border-blue-300 focus-within:ring-4 focus-within:ring-blue-500/10">
              <textarea value={message} onChange={event => setMessage(event.target.value)} rows={2} placeholder={t('assistant.inputPlaceholder')} className="min-h-[48px] flex-1 resize-none border-0 bg-transparent px-2 py-1.5 text-sm leading-6 text-slate-800 outline-none placeholder:text-slate-400" />
              <button type="submit" disabled={!message.trim() || running} className="flex h-10 shrink-0 items-center gap-2 rounded-xl bg-slate-900 px-4 text-sm font-semibold text-white transition hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-40"><Send className="h-4 w-4" />{t('assistant.send')}</button>
            </div>
          </form>
        </section>

        <aside className="space-y-4">
          <section className="surface p-5">
            <div className="flex items-center gap-2"><div className="flex h-8 w-8 items-center justify-center rounded-lg bg-violet-50 text-violet-600"><WandSparkles className="h-4 w-4" /></div><h2 className="text-sm font-semibold text-slate-900">{t('assistant.contextTitle')}</h2></div>
            <p className="mt-3 text-xs leading-5 text-slate-500">{t('assistant.contextDescription')}</p>
            <textarea value={context} onChange={event => setContext(event.target.value)} rows={8} placeholder={t('assistant.contextPlaceholder')} className="input mt-4 resize-none text-xs leading-5" />
          </section>
          <section className="surface p-5">
            <h2 className="text-sm font-semibold text-slate-900">{t('assistant.engineeringTitle')}</h2>
            <div className="mt-4 space-y-3">
              {[t('assistant.engineering1'), t('assistant.engineering2'), t('assistant.engineering3')].map(item => <div key={item} className="flex gap-2 text-xs leading-5 text-slate-500"><Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />{item}</div>)}
            </div>
            <Link to="/models" className="mt-5 flex items-center gap-1 text-xs font-semibold text-blue-600 hover:text-blue-700">{t('assistant.viewModels')} <ChevronRight className="h-3.5 w-3.5" /></Link>
          </section>
        </aside>
      </div>
    </div>
  )
}
