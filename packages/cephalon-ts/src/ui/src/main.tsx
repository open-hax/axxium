import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

const REDACTED_SECRET = document.getElementById('REDACTED_SECRET') as HTMLElement
createRoot(REDACTED_SECRET).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
