import { theme, type ThemeConfig } from 'antd';

/**
 * Ant Design 主题 —— 对齐 DeepSeek Harness（dsh-client-ui-theme）。
 * <p>
 * 取值来源：`@deepseek-ai/dsh-client-ui-theme` 的 `--dsw-*` 别名 token（暗色/亮色两套），
 * 与 `styles/theme.css` 的同名 token 一一对应；两套色板跟随系统（`prefers-color-scheme`）。
 * 纪律：单强调色、系统字体栈、半透明 hairline 边框、小圆角、浮层用"细描边 + 大模糊"。
 */
const fontFamily =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif";

function build(mode: 'light' | 'dark'): ThemeConfig {
  const dark = mode === 'dark';

  /* DSH 色板：暗色用 deepseek-400 做强调，亮色用 deepseek-500 */
  const accent = dark ? '#7aaaff' : '#4176e6';
  const bgBase = dark ? '#151517' : '#ffffff';
  const bgContainer = dark ? '#1b1b1c' : '#f9fafb';
  const bgElevated = dark ? '#232324' : '#ffffff';
  const bgSpotlight = dark ? '#2c2c2e' : '#ffffff';
  const border = dark ? '#ffffff1f' : '#0000001a';
  const borderSecondary = dark ? '#ffffff0f' : '#0000000a';
  const text = dark ? '#f9fafb' : '#0f1115';
  const textSecondary = dark ? '#cfd3d6' : '#61666b';
  const textTertiary = dark ? '#adb2b8' : '#81858c';
  const textQuaternary = dark ? '#81858c' : '#adb2b8';

  return {
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: accent,
      colorInfo: accent,
      colorError: dark ? '#f25a5a' : '#ec1313',
      colorWarning: dark ? '#f7ad31' : '#f59e0b',
      colorSuccess: dark ? '#4ed17e' : '#22c55e',

      colorBgBase: bgBase,
      colorBgContainer: bgContainer,
      colorBgElevated: bgElevated,
      colorBgLayout: bgBase,
      colorBgSpotlight: bgSpotlight,

      colorBorder: border,
      colorBorderSecondary: borderSecondary,
      colorSplit: borderSecondary,

      colorText: text,
      colorTextSecondary: textSecondary,
      colorTextTertiary: textTertiary,
      colorTextQuaternary: textQuaternary,

      borderRadius: 8, // DSH radius-sm
      borderRadiusSM: 4, // radius-xs
      borderRadiusLG: 12, // radius-md

      fontFamily,
      fontSize: 13,
      controlHeight: 28,
      controlHeightSM: 24,
      controlHeightLG: 34,
      boxShadow: 'none',
      // DSH shadow-lv3：细描边 + 大模糊，而不是一团黑影
      boxShadowSecondary: dark
        ? '0 0 1px 0 #0003, 0 0 4px 0 #00000005, 0 12px 32px 0 #00000014'
        : '0 0 1px 0 #0000001f, 0 4px 16px 0 #0000000f',
      wireframe: false,
    },
    components: {
      Button: {
        primaryShadow: 'none',
        defaultShadow: 'none',
        dangerShadow: 'none',
        defaultBg: bgElevated,
        defaultBorderColor: border,
        paddingInline: 10,
      },
      Input: {
        activeShadow: 'none',
        colorBgContainer: dark ? '#0f1115' : '#ffffff',
        paddingInline: 8,
      },
      Select: {
        optionSelectedBg: dark ? '#2c2c2e' : '#f1f3f5',
        optionActiveBg: dark ? '#232324' : '#f5f6f7',
        colorBgContainer: dark ? '#0f1115' : '#ffffff',
      },
      Tooltip: {
        colorBgSpotlight: bgSpotlight,
        colorTextLightSolid: text,
        borderRadius: 8,
      },
      Empty: {
        colorTextDescription: textTertiary,
      },
      Tag: {
        defaultBg: bgElevated,
        defaultColor: textSecondary,
      },
      Drawer: {
        colorBgElevated: bgContainer,
      },
      Modal: {
        contentBg: bgContainer,
        headerBg: bgContainer,
      },
      Segmented: {
        itemSelectedBg: dark ? '#2c2c2e' : '#ffffff',
        trackBg: dark ? '#0f1115' : '#f5f6f7',
      },
    },
  };
}

/** 暗色（DSH dark） */
export const darkTheme: ThemeConfig = build('dark');

/** 亮色（DSH light） */
export const lightTheme: ThemeConfig = build('light');
