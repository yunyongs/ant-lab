import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App.tsx';
import { initConvention } from './features/format/convention.ts';
import './styles/reset.css';
import './styles/tokens.css';
import './styles/global.css';

initConvention();

const container = document.getElementById('root');
if (!container) throw new Error('Missing #root element');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
