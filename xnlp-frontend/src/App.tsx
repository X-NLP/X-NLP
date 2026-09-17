import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'

const Assistant = lazy(() => import('./pages/Assistant'))
const Canvas = lazy(() => import('./pages/Canvas'))
const Compare = lazy(() => import('./pages/Compare'))
const Datasets = lazy(() => import('./pages/Datasets'))
const Evaluation = lazy(() => import('./pages/Evaluation'))
const Models = lazy(() => import('./pages/Models'))
const Knowledge = lazy(() => import('./pages/Knowledge'))
const Playground = lazy(() => import('./pages/Playground'))
const Benchmark = lazy(() => import('./pages/Benchmark'))
const NlpWorkbench = lazy(() => import('./pages/NlpWorkbench'))
const WasteFlow = lazy(() => import('./pages/WasteFlow'))
const PipelineRuns = lazy(() => import('./pages/PipelineRuns'))

function RouteLoading() {
  return (
    <div className="space-y-5" aria-busy="true" aria-label="Loading workspace">
      <div className="h-8 w-56 animate-pulse rounded-xl bg-slate-200" />
      <div className="grid gap-4 md:grid-cols-3">
        {[0, 1, 2].map(item => (
          <div key={item} className="h-32 animate-pulse rounded-2xl border border-slate-200 bg-white" />
        ))}
      </div>
      <div className="h-80 animate-pulse rounded-2xl border border-slate-200 bg-white" />
    </div>
  )
}

export default function App() {
  return (
    <Layout>
      <Suspense fallback={<RouteLoading />}>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/assistant" element={<Assistant />} />
          <Route path="/models" element={<Models />} />
          <Route path="/playground" element={<Playground />} />
          <Route path="/benchmark" element={<Benchmark />} />
          <Route path="/nlp" element={<NlpWorkbench />} />
          <Route path="/datasets" element={<Datasets />} />
          <Route path="/knowledge" element={<Knowledge />} />
          <Route path="/evaluation" element={<Evaluation />} />
          <Route path="/pipeline-runs" element={<PipelineRuns />} />
          <Route path="/canvas" element={<Canvas />} />
          <Route path="/compare" element={<Compare />} />
          <Route path="/waste" element={<WasteFlow />} />
        </Routes>
      </Suspense>
    </Layout>
  )
}
