/**
 * 组件渲染检查（无浏览器）：用 react-dom/server 真渲染证据查看器，断言它**确实渲染出**该有的结构，
 * 补上"前端只验纯逻辑、不验渲染"这个已知缺口（逻辑回归见 scripts/smoke.ts）。
 *
 * 为什么要它：P1-3 的时间线视图把"对齐"画成双轴时间轴，泳道/标记/高亮/刻度都是**渲染结果**，
 * 纯逻辑测试碰不到；而 web/package.json 里没有 playwright/puppeteer，所以用 SSR 静态渲染做最小验证
 * （点选、悬停这类交互仍只能人工在 5173 上看）。
 *
 * 运行（注意要用 cjs：react-dom/server 是 CJS，esm 下会报 dynamic require of "stream"）：
 *   node_modules\.bin\esbuild.cmd scripts/render-check.tsx --bundle --platform=node --format=cjs \
 *     --jsx=automatic --define:import.meta.env={} --outfile=scripts/.render-check.cjs
 *   node scripts/.render-check.cjs
 */
import { renderToStaticMarkup } from 'react-dom/server';
import TimelineView from '../src/components/evidence/TimelineView';
import InvestigationTimeline from '../src/components/InvestigationTimeline';
import { buildTimeline } from '../src/utils/investigation';

const item = {
  id: 'EV-01',
  index: 1,
  toolName: 'query_correlated_timeline',
  dsId: 'cross_source',
  entityCode: 'timeline',
  title: '时间线',
  typeLabel: 'Cross-Source',
  kind: 'cross',
  target: 'ds_log_es+ds_pg_payment',
  conditions: ['keyword = "限流"', '时间桶 = 5 分钟'],
  arguments: '{"keyword":"限流","bucketMinutes":5,"entityCode":"payments"}',
  step: 1,
  timestamp: Date.now(),
  durationMs: 145,
  rowsCount: 3,
  interpretation: '命中 3 时间桶',
  rawRef: 'correlate://ds_log_es+ds_pg_payment@2026-03-26T16:00:00Z~2026-03-27T15:59:59Z',
  refSummary: '',
  query: {
    dsId: 'cross_source',
    entityCode: 'timeline',
    columns: ['bucketStart', 'logCount', 'orderCount', 'logSummary', 'orderSummary'],
    rows: [
      {
        bucketStart: '2026-03-27 10:30:00',
        logCount: 1,
        orderCount: 1,
        logSummary: 'WARN · group-buy-market · 用户访问拼团项目被限流',
        orderSummary: 'NO20260327001 · TIMEOUT · GW_TIMEOUT',
        _rawRef: 'group-buy-market-log-2026.03.27#doc1 | order_no=NO20260327001',
      },
      { bucketStart: '2026-03-27 10:35:00', logCount: 0, orderCount: 1, logSummary: '', orderSummary: 'NO2 · SUCCESS' },
      { bucketStart: '2026-03-27 14:35:00', logCount: 1, orderCount: 0, logSummary: 'ERROR · csdn 限流', orderSummary: '' },
    ],
    total: 3,
    truncated: false,
    costMs: 145,
    rawRef: 'correlate://ds_log_es+ds_pg_payment@window',
    warnings: ['已对齐：日志@ds_log_es（1 条） + 实体@ds_pg_payment（2 条）；桶粒度 5 分钟；时间按 Asia/Shanghai 解释'],
  },
};

const html = renderToStaticMarkup(
  <TimelineView
    item={item as never}
    sources={[
      { dsId: 'ds_pg_payment', entityCode: 'payments', entityName: '支付流水', capability: 'entity_query', fields: [] },
    ] as never}
  />,
);

let failed = 0;
const check = (name: string, cond: boolean, extra?: unknown) => {
  if (!cond) failed += 1;
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${extra === undefined ? '' : ` → ${extra}`}`);
};
const count = (needle: string) => html.split(needle).length - 1;
// SSR 会在相邻文本节点之间插入注释占位，做文本断言前先去掉
const text = html.replace(/<!--[\s\S]*?-->/g, '');

check('渲染出对齐来源', text.includes('对齐来源') && text.includes('ds_log_es'));
check('渲染出两条泳道标签', text.includes('日志') && text.includes('支付流水'));
check('实体泳道用对齐实体名（不是"实体"）', !text.includes('>实体<'));
check(
  '渲染出桶粒度与两侧条数摘要',
  text.includes('桶粒度 5 分钟') && text.includes('日志 2 条') && text.includes('支付流水 2 条'),
  text.match(/桶粒度[^<]*/)?.[0],
);
check('渲染出时间刻度', text.includes('10:30:00') && text.includes('14:35:00'));
// 3 个桶：两侧都有(1,1) → 2 个标记；单侧(0,1)、(1,0) → 各 1 个
check('标记数 = 各桶有效侧之和（4）', count('class="tl-marker"') === 4, count('class="tl-marker"'));
check('同一桶两侧都有 → 高亮标记 2 个 + 高亮竖线 1 条', count('data-both="true"') === 3, count('data-both="true"'));
check('未点击时不显示桶详情', !html.includes('tl-detail"'));
check('渲染出点击提示', text.includes('点击泳道上的点查看该时间桶'));
check(
  '把"对齐了谁/多大粒度"直接写在视图里（不依赖翻到 Interpretation）',
  text.includes('ds_log_es') && text.includes('ds_pg_payment') && text.includes('5 分钟'),
);
check('无假告警（两侧都有数据时不报 0 条）', !text.includes('0 条：不等于'));

/* ---- 会话多轮（追加式事件流）：两轮的问题都必须渲染出来 ---- */
const t2 = 10_000;
const shift = (list: Array<Record<string, unknown>>, delta: number) =>
  list.map((e) => ({ ...e, timestamp: (e.timestamp as number) + delta }));
const turn1 = [
  { type: 'analysis', subType: 'analysis_strategy', step: 1, content: '下一步策略: 先看订单', completed: false, sessionId: 's', timestamp: 1100 },
  { type: 'summary', subType: 'full', step: 4, content: '第一轮结论：PAY_TIMEOUT', completed: true, sessionId: 's', timestamp: 1410 },
];
const turn2 = shift(
  [
    { type: 'analysis', subType: 'analysis_strategy', step: 1, content: '下一步策略: 再看另一笔', completed: false, sessionId: 's', timestamp: 1100 },
    { type: 'summary', subType: 'full', step: 4, content: '第二轮结论：余额不足', completed: true, sessionId: 's', timestamp: 1410 },
  ],
  t2,
);
const turns = [
  { question: '第一问：为什么下单失败？', at: 1000 },
  { question: '第二问：那另外那笔呢？', at: t2 + 500 },
];
const tlActions = buildTimeline([...turn1, ...turn2] as never, false, turns);
const tlHtml = renderToStaticMarkup(
  <InvestigationTimeline actions={tlActions} activeEvidence={null} onCite={() => {}} />,
);
const tlText = tlHtml.replace(/<!--[\s\S]*?-->/g, '');
check('多轮：两轮的问题都渲染在时间线上', tlText.includes('第一问') && tlText.includes('第二问'), tlText.match(/USER QUESTION[^<]*/g));
check('多轮：问题节点带序号（第二轮）', tlText.includes('USER QUESTION #2'));
check(
  '多轮：两轮各自的结论都在（第二轮没被第一轮吃掉）',
  tlText.includes('第一轮结论') && tlText.includes('第二轮结论'),
  tlText.match(/(第[一二]轮结论)/g),
);

console.log(failed === 0 ? '\nRENDER ALL PASS' : `\n${failed} RENDER CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
