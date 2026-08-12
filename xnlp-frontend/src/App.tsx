import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import Assistant from './pages/Assistant'
import Canvas from './pages/Canvas'
import Compare from './pages/Compare'
import Dashboard from './pages/Dashboard'
import Datasets from './pages/Datasets'
import Evaluation from './pages/Evaluation'
import Models from './pages/Models'
import NlpWorkbench from './pages/NlpWorkbench'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/assistant" element={<Assistant />} />
        <Route path="/models" element={<Models />} />
        <Route path="/nlp" element={<NlpWorkbench />} />
        <Route path="/datasets" element={<Datasets />} />
        <Route path="/evaluation" element={<Evaluation />} />
        <Route path="/canvas" element={<Canvas />} />
        <Route path="/compare" element={<Compare />} />
      </Routes>
    </Layout>
  )
}
