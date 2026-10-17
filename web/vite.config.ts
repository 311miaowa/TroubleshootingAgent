import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 证据面板前端（M3.3）
// 后端已开 @CrossOrigin，故直接经 VITE_API_BASE 跨域访问 8099，无需 dev proxy。
// 若希望走同源代理，取消下方 server.proxy 注释并把 VITE_API_BASE 设为空串。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // proxy: {
    //   '/api': {
    //     target: 'http://127.0.0.1:8099',
    //     changeOrigin: true,
    //   },
    // },
  },
  build: {
    rollupOptions: {
      output: {
        // 拆出框架与组件库：单文件 578 KB 会一直触发 "chunks larger than 500 kB" 警告，
        // 且应用代码一改就整包失效（缓存命中率低）。拆包不减少总字节数，只解决这两点。
        // 注意：这里只做"分块"，不做"减包"——实测 antd 的 tree-shaking 已生效
        // （rc-table / rc-select / rc-picker / dayjs / @ant-design/icons 在产物中出现 0 次），
        // 无需再引入 babel-plugin-import 之类的按需插件。
        manualChunks(id) {
          // 注意：这里用 indexOf 而不是 includes —— 本项目的 tsc -b 以较低 lib 目标检查
          // vite.config.ts，String.prototype.includes 会报 TS2550。
          if (id.indexOf('node_modules') === -1) {
            return undefined;
          }
          if (id.indexOf('react-dom') !== -1 || id.indexOf('/react/') !== -1
              || id.indexOf('scheduler') !== -1) {
            return 'vendor-react';
          }
          if (id.indexOf('antd') !== -1 || id.indexOf('@ant-design') !== -1
              || id.indexOf('/rc-') !== -1) {
            return 'vendor-antd';
          }
          return 'vendor';
        },
      },
    },
  },
});

