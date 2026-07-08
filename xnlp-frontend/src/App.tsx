import { Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Models from './pages/Models'
import Datasets from './pages/Datasets'
import Evaluation from './pages/Evaluation'
import Compare from './pages/Compare'
import Canvas from './pages/Canvas'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/models" element={<Models />} />
        <Route path="/datasets" element={<Datasets />} />
        <Route path="/evaluation" element={<Evaluation />} />
        <Route path="/canvas" element={<Canvas />} />
        <Route path="/compare" element={<Compare />} />
      </Routes>
    </Layout>
  )
}
