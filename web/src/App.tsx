import { useEffect, useState } from 'react';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import Workbench from './components/Workbench';
import { darkTheme, lightTheme } from './theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * 跟随系统亮/暗（与 DSH 的 `system` 偏好一致）。
 * 首屏由 index.html 的内联脚本先定一次 data-theme（避免闪色），这里只负责随系统变化更新。
 */
function useSystemMode(): 'light' | 'dark' {
  const [mode, setMode] = useState<'light' | 'dark'>(() =>
    typeof window !== 'undefined' && window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light',
  );

  useEffect(() => {
    const mq = window.matchMedia(DARK_QUERY);
    const onChange = (e: MediaQueryListEvent) => setMode(e.matches ? 'dark' : 'light');
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  return mode;
}

export default function App() {
  const mode = useSystemMode();

  // CSS 的亮/暗两套色板靠 :root[data-theme=…] 切换
  useEffect(() => {
    document.documentElement.dataset.theme = mode;
  }, [mode]);

  return (
    <ConfigProvider locale={zhCN} theme={mode === 'dark' ? darkTheme : lightTheme}>
      <AntApp>
        <Workbench />
      </AntApp>
    </ConfigProvider>
  );
}
