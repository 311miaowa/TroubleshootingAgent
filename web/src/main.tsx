import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

// 字体（本地打包，避免外网依赖）
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/500.css';

import 'antd/dist/reset.css';
import './styles/theme.css';

import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
