/**
 * 纯逻辑冒烟测试（无浏览器）：用**真实 e2e 输出的形态**验证
 * 证据注册表 / 引用锚点（关键：模型改写截断后仍能命中）/ Timeline / Diagnosis。
 *
 * 运行：npx esbuild scripts/smoke.ts --bundle --platform=node --format=esm --outfile=scripts/.smoke.mjs && node scripts/.smoke.mjs
 */
import type { AgentEvent } from '../src/types/events';
import type { SourceHealth } from '../src/api/datasource';
import { buildEvidence, buildSources, humanizeArgs, matchEvidenceIds, mergeHealthSources } from '../src/utils/evidence';
import { buildConclusion, buildDiagnosis, buildTimeline, eventsOfCurrentTurn, readSupervision } from '../src/utils/investigation';
import { parseResultRawRef, parseRowRawRef, rowRawRefLabel } from '../src/utils/rawRef';
import { datasourceTipLines, rollupDatasourceState, sessionQueryNote, toDatasourceState } from '../src/utils/status';
import { axisTicks, buildCrossTimeline, hhmmss, parseBucketMinutes, parseEntityCode, positionOf } from '../src/utils/timeline';

const DESCRIBE = JSON.stringify([
  {
    dsId: 'ds_log_es',
    entityCode: 'logs',
    entityName: '应用日志',
    capability: 'log_search',
    physicalName: 'group-buy-market-log-*',
    timeField: '@timestamp',
    fields: [{ fieldName: 'message', fieldLabel: '日志内容', fieldType: 'text', sensitive: false }],
  },
  {
    dsId: 'ds_order_mysql',
    entityCode: 'orders',
    entityName: '订单',
    capability: 'entity_query',
    physicalName: 'ai_order',
    timeField: 'create_time',
    fields: [{ fieldName: 'order_no', fieldLabel: '订单号', fieldType: 'varchar', sensitive: false }],
  },
]);

const FULL_MSG = '文章发布失败 - 触发 API 限流 - 账号：quick_publisher_88，1 小时内已发布 15 篇，超过限制';

const LOGS = JSON.stringify({
  dsId: 'ds_log_es',
  entityCode: 'logs',
  columns: ['level', 'message', '@timestamp', 'application'],
  rows: [
    {
      level: 'ERROR',
      message: FULL_MSG,
      '@timestamp': '2026-03-27T14:35:22+08:00',
      application: 'csdn-auto-publisher',
      account_id: 'quick_publisher_88',
      _rawRef: 'group-buy-market-log-2026.03.27#omGXL50BuQgHrtIGCbdf',
    },
  ],
  total: 2,
  truncated: false,
  costMs: 19,
  rawRef: 'es://ds_log_es/logs/group-buy-market-log-*@2026-03-19T16:00:00Z~2026-04-04T16:00:00Z',
  warnings: [],
});

const ORDERS_EMPTY = JSON.stringify({
  dsId: 'ds_order_mysql',
  entityCode: 'orders',
  columns: ['order_no', 'status'],
  rows: [],
  total: 0,
  truncated: false,
  costMs: 12,
  rawRef: 'mysql://ds_order_mysql/orders/ai_order@2026-03-19T16:00:00Z~2026-04-04T16:00:00Z',
  warnings: [],
});

/** 关键：模拟真实模型输出——原文被截断、时间戳格式被改写 */
const SUMMARY = [
  '# 限流排查结论',
  '',
  '## 根因',
  '命中日志显示：文章发布失败 - 触发 API 限流',
  '',
  '## 关键发现',
  '| 时间 | 级别 | 应用 |',
  '|---|---|---|',
  '| 2026-03-27 14:35:22 | ERROR | csdn-auto-publisher |',
  '',
  '## 建议',
  '1. 优先核查账号 quick_publisher_88 的发布行为',
].join('\n');

const SUPERVISION =
  '🔍 质量评估：流程完整。\n💡 改进建议：\n- 补齐英文关键词（rate limit / throttle）\n- 补充订单数据交叉验证\n🚦 监督结果: OPTIMIZE';

const events = [
  { type: 'tool_call', subType: 'start', toolName: 'describe_entity', dsId: null, entityCode: null, step: 1, sessionId: 's', arguments: '{}', timestamp: 1000 },
  { type: 'tool_call', subType: 'result', toolName: 'describe_entity', dsId: null, entityCode: null, step: 1, sessionId: 's', arguments: '{}', result: DESCRIBE, timestamp: 1010 },
  { type: 'analysis', subType: 'analysis_strategy', step: 1, content: '下一步策略: 按关键字检索日志验证限流假设', completed: false, sessionId: 's', timestamp: 1020 },
  { type: 'tool_call', subType: 'start', toolName: 'query_logs', dsId: 'ds_log_es', entityCode: 'logs', step: 1, sessionId: 's', arguments: '{"keyword":"限流","limit":20}', timestamp: 1100 },
  { type: 'tool_call', subType: 'result', toolName: 'query_logs', dsId: 'ds_log_es', entityCode: 'logs', step: 1, sessionId: 's', arguments: '{"keyword":"限流","limit":20}', result: LOGS, timestamp: 1120 },
  { type: 'tool_call', subType: 'start', toolName: 'query_orders', dsId: 'ds_order_mysql', entityCode: 'orders', step: 1, sessionId: 's', arguments: '{"orderNo":"NO20260327001"}', timestamp: 1200 },
  { type: 'tool_call', subType: 'result', toolName: 'query_orders', dsId: 'ds_order_mysql', entityCode: 'orders', step: 1, sessionId: 's', arguments: '{"orderNo":"NO20260327001"}', result: ORDERS_EMPTY, timestamp: 1215 },
  { type: 'supervision', subType: 'score', step: 1, content: SUPERVISION, completed: false, sessionId: 's', timestamp: 1300 },
  { type: 'summary', subType: 'full', step: 4, content: SUMMARY, completed: true, sessionId: 's', timestamp: 1410 },
  { type: 'complete', content: '执行完成', completed: true, sessionId: 's', timestamp: 1420 },
] as unknown as AgentEvent[];

let failed = 0;
function check(name: string, cond: boolean, extra?: unknown) {
  if (!cond) failed += 1;
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${extra === undefined ? '' : ` → ${JSON.stringify(extra)}`}`);
}

/* 1) 数据源 */
const sources = buildSources(events);
check('数据源清单（2 实体）', sources.length === 2, sources.map((s) => s.dsId));

/* 2) 证据注册表 */
const evidence = buildEvidence(events, sources);
check('证据数 = 2（仅 query_*，探查不计证据）', evidence.length === 2, evidence.map((e) => e.id));
check('日志证据 TYPE = Log Search', evidence[0].typeLabel === 'Log Search', evidence[0].typeLabel);
check('订单证据 TYPE = Database Query', evidence[1].typeLabel === 'Database Query', evidence[1].typeLabel);
check('证据 SOURCE = 物理定位', evidence[0].target === 'group-buy-market-log-*', evidence[0].target);
check('QUERY 条件可读化', evidence[0].conditions.some((c) => c.includes('keyword')), evidence[0].conditions);
check('0 命中给出"不支持假设"解读', evidence[1].interpretation.includes('暂不支持当前假设'), evidence[1].interpretation);

/* 3) 关键回归：模型改写 / 截断后仍能锚定证据 */
const paraphrased = '命中日志显示：文章发布失败 - 触发 API 限流';
check('截断原文（缺账号后缀）仍命中 EV-01', matchEvidenceIds(paraphrased, evidence).includes('EV-01'));
check('账号标识命中 EV-01', matchEvidenceIds('核查账号 quick_publisher_88 的行为', evidence).includes('EV-01'));
// 刻意设计：时间戳/应用名这类通用值不算「有数据支撑」，否则表头与泛化描述会被误判为 Confirmed
check(
  '仅出现时间戳不认定为证据锚点（避免误判）',
  !matchEvidenceIds('| 2026-03-27 14:35:22 | ERROR |', evidence).includes('EV-01'),
);
check('无关文本不误命中', matchEvidenceIds('今天天气不错，服务一切正常', evidence).length === 0);

/* 4) Timeline */
const actions = buildTimeline(events, false);
const names = actions.map((a) => a.name);
check(
  '动作名序列符合设计文档',
  names.join(' / ') ===
    'INSPECT DATA SOURCES / UNDERSTAND INCIDENT / QUERY APPLICATION LOGS / QUERY ORDER DATABASE / VALIDATE FINDINGS / DIAGNOSE',
  names,
);
const qLogs = actions.find((a) => a.name === 'QUERY APPLICATION LOGS')!;
check('查询动作带 Evidence 引用', qLogs.evidenceIds[0] === 'EV-01', qLogs.evidenceIds);
check('查询动作带 RESULT', qLogs.resultText === '2 matches', qLogs.resultText);
check('查询动作带耗时', qLogs.durationMs === 19, qLogs.durationMs);
check('查询动作带 Purpose（取自 Agent 下一步策略）', (qLogs.purpose ?? '').includes('限流'), qLogs.purpose);
check('监督动作 OPTIMIZE → Completed', actions.find((a) => a.name === 'VALIDATE FINDINGS')!.status === 'completed');
const runningActions = buildTimeline(events.slice(0, 5), true);
check('进行中会补 Pending 占位', runningActions.some((a) => a.status === 'pending'), runningActions.map((a) => a.status));

/* 5) Diagnosis */
const conclusion = buildConclusion(events);
const diag = buildDiagnosis(events, evidence, conclusion, false)!;
check('Diagnosis 可派生', !!diag);
check('根因标题非空', diag.rootCauseHeadline.length > 0, diag.rootCauseHeadline);
check(
  '该句被列为 Confirmed 并带 EV-01',
  diag.confirmed.some((f) => f.evidenceIds.includes('EV-01')),
  diag.confirmed.map((f) => f.evidenceIds),
);
check('0 命中证据进入 Needs verification', diag.needsVerification.some((t) => t.includes('EV-02')), diag.needsVerification);
check('OPTIMIZE 进入 Needs verification', diag.needsVerification.some((t) => t.includes('OPTIMIZE')));
check('下一步取到 Agent 建议', diag.nextSteps.some((t) => t.includes('rate limit')), diag.nextSteps);
check('Confidence = Medium（有命中但校验 OPTIMIZE）', diag.confidence === 'Medium', {
  c: diag.confidence,
  why: diag.confidenceReason,
});
check('evidenceBacked = true', diag.evidenceBacked === true);

const emptyDiag = buildDiagnosis(events, [], SUMMARY, false)!;
check('无证据时 Confidence = Unknown', emptyDiag.confidence === 'Unknown' && emptyDiag.evidenceBacked === false);

/* 6) Supervision 提取 */
const sup = readSupervision(events);
check('监督结论 = OPTIMIZE', sup.verdict === 'OPTIMIZE', sup.verdict);
check('监督建议提取 2 条', sup.suggestions.length === 2, sup.suggestions);
check(
  '未正常收尾 → 计入 Needs verification',
  buildDiagnosis(events.slice(0, -1), evidence, conclusion, false)!.needsVerification.some((t) => t.includes('完成标识')),
);

/* 7) 数据源权威状态（M4.2）：三态→两态映射、hint 文案、清单合并、会话辅助信息 */
const healthyEs: SourceHealth = {
  dsId: 'ds_log_es',
  dsType: 'elasticsearch',
  healthy: true,
  message: null,
  costMs: 9,
  hint: null,
  entities: [{ entityCode: 'logs', entityName: '应用日志', capability: 'log_search' }],
};
const brokenMysql: SourceHealth = {
  dsId: 'ds_order_mysql',
  dsType: 'mysql',
  healthy: false,
  message:
    "健康检查失败 dsId=ds_order_mysql：Failed to obtain JDBC Connection（根因：Access denied for user 'ai_ro'@'localhost' (using password: NO)）",
  costMs: 5012,
  hint: '未能取到连接密码：请注入环境变量 DS_ORDER_MYSQL_PASSWORD（来源 ai_data_source.conn_config.passwordEnv）',
  entities: [{ entityCode: 'orders', entityName: '订单', capability: 'entity_query' }],
};

check('healthy=true → Connected', toDatasourceState(healthyEs) === 'connected');
check('healthy=false → Disconnected', toDatasourceState(brokenMysql) === 'disconnected');
check('无权威状态 → Unknown（不再谎报 Connected）', toDatasourceState(undefined) === 'unknown');
check('entry 汇总：有坏源即 Disconnected', rollupDatasourceState(['connected', 'disconnected']) === 'disconnected');
check('entry 汇总：全绿才 Connected', rollupDatasourceState(['connected', 'connected']) === 'connected');
check('entry 汇总：含 Unknown 不算全绿', rollupDatasourceState(['connected', 'unknown']) === 'unknown');
check('entry 汇总：空清单为 Unknown', rollupDatasourceState([]) === 'unknown');

const brokenTip = datasourceTipLines('disconnected', brokenMysql, sessionQueryNote(events, 'ds_order_mysql'));
check('悬停含失败原因', brokenTip.some((l) => l.startsWith('原因：') && l.includes('Failed to obtain JDBC Connection')), brokenTip);
check('悬停含可操作建议（环境变量名）', brokenTip.some((l) => l.includes('建议：') && l.includes('DS_ORDER_MYSQL_PASSWORD')), brokenTip);
check('悬停标注检查耗时', brokenTip.some((l) => l.includes('5012ms')), brokenTip);
check('悬停保留"本次会话"辅助信息', brokenTip.some((l) => l.startsWith('本次会话：')), brokenTip);
check(
  '端点不可用时明说原因',
  datasourceTipLines('unknown', undefined, null, '健康端点请求失败 HTTP 500').some((l) => l.includes('健康端点请求失败')),
);
check('健康源悬停不提"失败"', datasourceTipLines('connected', healthyEs).every((l) => !l.includes('失败')));

check('会话辅助：查过且无告警', (sessionQueryNote(events, 'ds_log_es') ?? '').includes('成功'), sessionQueryNote(events, 'ds_log_es'));
check('会话辅助：没查过如实说', sessionQueryNote(events, 'ds_nope') === '尚未查询');
const warnEvents = [
  ...events,
  {
    type: 'tool_call',
    subType: 'result',
    toolName: 'query_orders',
    dsId: 'ds_order_mysql',
    entityCode: 'orders',
    step: 1,
    sessionId: 's',
    arguments: '{}',
    result: JSON.stringify({ rows: [], warnings: ['MySQL 查询异常：Failed to obtain JDBC Connection'] }),
    timestamp: 1500,
  },
] as unknown as AgentEvent[];
check(
  '会话辅助：最近一次有告警 → 返回告警',
  (sessionQueryNote(warnEvents, 'ds_order_mysql') ?? '').includes('返回告警'),
  sessionQueryNote(warnEvents, 'ds_order_mysql'),
);

/* 关键：没发起任何调查时，面板也要能看到"哪个源是坏的" */
const healthMap = new Map<string, SourceHealth>([
  [healthyEs.dsId, healthyEs],
  [brokenMysql.dsId, brokenMysql],
]);
const fromHealthOnly = mergeHealthSources([], healthMap);
check('无探查结果时清单来自端点（2 实体）', fromHealthOnly.length === 2, fromHealthOnly.map((s) => `${s.dsId}:${s.entityCode}`));
check('端点实体保留 dsId/能力', fromHealthOnly.some((s) => s.dsId === 'ds_order_mysql' && s.capability === 'entity_query'));
const mergedSources = mergeHealthSources(sources, healthMap);
check('合并后不产生重复条目', mergedSources.length === sources.length, mergedSources.map((s) => `${s.dsId}:${s.entityCode}`));
check(
  '合并保留探查到的物理定位（证据面板仍可用）',
  mergedSources.find((s) => s.dsId === 'ds_log_es')?.physicalName === 'group-buy-market-log-*',
);

/* 8) 跨源关联（P1）：证据类型识别 / 参数可读化 / rawRef 解析 */
const CORRELATED = JSON.stringify({
  dsId: 'cross_source',
  entityCode: 'timeline',
  columns: ['bucketStart', 'logCount', 'orderCount', 'logSummary', 'orderSummary'],
  rows: [
    {
      bucketStart: '2026-03-27 10:30:00',
      logCount: 1,
      orderCount: 1,
      logSummary: 'WARN · group-buy-market · 用户访问拼团项目被限流',
      orderSummary: 'NO20260327001 · FAILED · PAY_TIMEOUT · 199.00',
      _rawRef: 'group-buy-market-log-2026.03.27#oGGNL50BuQgHrtIGkLeN | order_no=NO20260327001',
    },
  ],
  total: 1,
  truncated: false,
  costMs: 42,
  rawRef: 'correlate://ds_log_es+ds_order_mysql@2026-03-26T16:00:00Z~2026-03-27T15:59:59Z',
  warnings: ['已对齐：日志@ds_log_es（1 条） + 订单@ds_order_mysql（1 条）；桶粒度 1 分钟；时间按 Asia/Shanghai 解释'],
});
const correlatedEvents = [
  {
    type: 'tool_call',
    subType: 'result',
    toolName: 'query_correlated_timeline',
    dsId: 'cross_source',
    entityCode: 'timeline',
    step: 1,
    sessionId: 's',
    arguments: '{"keyword":"限流","bucketMinutes":1}',
    result: CORRELATED,
    timestamp: 1600,
  },
] as unknown as AgentEvent[];

const crossEvidence = buildEvidence(correlatedEvents, []);
check('跨源对齐结果进入证据注册表', crossEvidence.length === 1, crossEvidence.length);
check('TYPE 识别为 Cross-Source', crossEvidence[0]?.typeLabel === 'Cross-Source', crossEvidence[0]?.typeLabel);
check('解读用「时间桶」而不是「记录/日志」', (crossEvidence[0]?.interpretation ?? '').includes('时间桶'), crossEvidence[0]?.interpretation);
check('证据保留结果级 correlate rawRef', (crossEvidence[0]?.rawRef ?? '').startsWith('correlate://'), crossEvidence[0]?.rawRef);
check(
  '参数可读化含时间桶粒度',
  humanizeArgs('{"keyword":"限流","bucketMinutes":5}', undefined).some((c) => c.includes('时间桶 = 5 分钟')),
  humanizeArgs('{"keyword":"限流","bucketMinutes":5}', undefined),
);

const parsedCorrelate = parseResultRawRef('correlate://ds_log_es+ds_order_mysql@a~b');
check('correlate:// 可解析', parsedCorrelate?.kind === 'correlate', parsedCorrelate);
check('解析出两侧来源', parsedCorrelate?.physicalName === 'ds_log_es+ds_order_mysql', parsedCorrelate?.physicalName);
check('原有 es:// 解析不回归', parseResultRawRef('es://ds/entity/index@a~b')?.kind === 'es');

const multiRow = parseRowRawRef('idx#doc1 | order_no=NO1');
check('桶级复合 _rawRef 识别为 multi', multiRow.kind === 'multi' && multiRow.parts?.length === 2, multiRow);
check('multi 回指标签可读', rowRawRefLabel(multiRow).includes('2 条原文引用'), rowRawRefLabel(multiRow));
check('单个 ES 文档 _rawRef 不回归', parseRowRawRef('idx#doc1').kind === 'es');

/* 9) 第三个数据源（P1-2）：pg:// 引用 + 通用过滤的可读化 */
const parsedPg = parseResultRawRef('pg://ds_pg_payment/payments/payment_transaction@a~b');
check('pg:// 结果级引用可解析', parsedPg?.kind === 'pg', parsedPg);
check('pg:// 解析出物理表名', parsedPg?.physicalName === 'payment_transaction', parsedPg?.physicalName);
check('pg:// 解析出 dsId/实体', parsedPg?.dsId === 'ds_pg_payment' && parsedPg?.entityCode === 'payments', parsedPg);
const filterConditions = humanizeArgs(
  '{"filters":[{"field":"status","operator":"EQ","value":"TIMEOUT"},{"field":"pay_channel","operator":"IN","value":"WECHAT,ALIPAY"}]}',
  undefined,
);
check('过滤条件渲染为可读写法', filterConditions.some((c) => c.includes('status = TIMEOUT')), filterConditions);
check('IN 操作符有专门写法', filterConditions.some((c) => c.includes('pay_channel ∈ WECHAT,ALIPAY')), filterConditions);

/* 10) 跨源时间线视图（P1-3）：泳道识别 / 时间解析 / 轴跨度 / 如实告警 */
const TL_COLUMNS = ['bucketStart', 'logCount', 'orderCount', 'logSummary', 'orderSummary'];
const TL_ROWS = [
  {
    bucketStart: '2026-03-27 10:30:00',
    logCount: 1,
    orderCount: 1,
    logSummary: 'WARN · 限流',
    orderSummary: 'NO20260327001 · TIMEOUT',
    _rawRef: 'idx#doc1 | order_no=NO20260327001',
  },
  { bucketStart: '2026-03-27 10:35:00', logCount: 1, orderCount: 0, logSummary: 'ERROR · 超时', orderSummary: '' },
  { bucketStart: '2026-03-27 14:35:00', logCount: 0, orderCount: 1, logSummary: '', orderSummary: 'NO20260327003 · FAILED' },
  { bucketStart: '不是时间', logCount: 0, orderCount: 1, logSummary: '', orderSummary: '脏数据' },
];
const tl = buildCrossTimeline(TL_COLUMNS, TL_ROWS, { bucketMinutes: 5, entityLabel: '支付流水' });

check('时间线：识别出 log/order 两条泳道', tl.lanes.map((l) => l.id).join(',') === 'log,order', tl.lanes.map((l) => l.id));
check('时间线：实体泳道用对齐的实体名', tl.lanes[1].label === '支付流水', tl.lanes[1].label);
check(
  '时间线：两侧条数按行统计（含无法定位到轴上的桶）',
  tl.lanes[0].total === 2 && tl.lanes[1].total === 3,
  tl.lanes.map((l) => l.total),
);
check(
  '时间线：桶时间可解析且严格升序',
  tl.buckets
    .filter((b) => b.at !== null)
    .map((b) => b.at as number)
    .every((v, i, arr) => i === 0 || v > arr[i - 1]),
  tl.buckets.map((b) => b.at),
);
check(
  '时间线：无法解析时间的桶被排除在轴外并计数告警',
  tl.warnings.some((w) => w.includes('无法解析')) && tl.buckets.filter((b) => b.at !== null).length === 3,
  tl.warnings,
);
check('时间线：桶宽取自工具入参', tl.bucketMs === 5 * 60_000, tl.bucketMs);
check(
  '时间线：位置归一化到 0~1（首桶 0、末桶 1）',
  positionOf(tl, tl.axisStart) === 0 && positionOf(tl, tl.axisEnd) === 1,
  [positionOf(tl, tl.axisStart), positionOf(tl, tl.axisEnd)],
);
check(
  '时间线：刻度含末桶且不超上限',
  axisTicks(tl, 5).length <= 6 && axisTicks(tl, 5).some((t) => t.at === tl.axisEnd),
  axisTicks(tl, 5),
);
check('时间线：桶级原文引用按 | 拆开', tl.buckets[0].rawRefs.length === 2, tl.buckets[0].rawRefs);
check(
  '时间线：两侧都有的桶可判定「时间上吻合」',
  tl.lanes.filter((l) => (tl.buckets[0].counts[l.id] ?? 0) > 0).length === 2,
  tl.buckets[0].counts,
);

const singleSided = buildCrossTimeline(
  ['bucketStart', 'logCount', 'orderCount'],
  [{ bucketStart: '2026-03-27 10:30:00', logCount: 2, orderCount: 0 }],
  {},
);
check(
  '时间线：单侧 0 条如实告警（不等于无关联）',
  singleSided.warnings.some((w) => w.includes('0 条') && w.includes('不等于')),
  singleSided.warnings,
);
check('时间线：单桶时跨度退化为一个桶宽（不除零）', singleSided.spanMs === 60_000, singleSided.spanMs);
check(
  '时间线：列名认不出泳道时退化为单泳道',
  buildCrossTimeline(['bucketStart', 'foo'], [{ bucketStart: '2026-03-27 10:30:00' }], {}).lanes.length === 1,
);
check(
  '时间线：解析工具入参（桶粒度 / 对齐实体）',
  parseBucketMinutes('{"bucketMinutes":5}') === 5 && parseEntityCode('{"entityCode":"payments"}') === 'payments',
  [parseBucketMinutes('{"bucketMinutes":5}'), parseEntityCode('{"entityCode":"payments"}')],
);
check('时间线：坏入参不抛异常', parseBucketMinutes('not-json') === null && parseEntityCode('') === null);
check('时间线：HH:mm:ss 截取', hhmmss('2026-03-27 10:30:00') === '10:30:00', hhmmss('2026-03-27 10:30:00'));

/* 11) 会话多轮（P1-4）：追加式事件流 —— 之前的问题与记录都必须留得住 */
const T2_OFFSET = 10_000;
const shiftEvents = (list: AgentEvent[], delta: number): AgentEvent[] =>
  list.map((e) => ({ ...e, timestamp: e.timestamp + delta })) as AgentEvent[];
const secondTurnSummary = '第二轮结论：另一笔失败是余额不足（INSUFFICIENT_BALANCE），与限流无关。';
// 第二轮 = 同一套事件结构（时间整体后移），只把 summary 的正文换掉，用于验证"结论取最后一次"而不是"最长"
const turn2Events = shiftEvents(
  events.map((e) => (e.type === 'summary' ? ({ ...e, content: secondTurnSummary } as AgentEvent) : e)),
  T2_OFFSET,
);
const multiTurnEvents = [...events, ...turn2Events];
const multiTurns = [
  { question: '第一问：为什么下单失败？', at: 1000 },
  { question: '第二问：那另外那笔呢？', at: T2_OFFSET + 500 },
];

const multiActions = buildTimeline(multiTurnEvents, false, multiTurns);
const multiNames = multiActions.map((a) => a.name);
check(
  '多轮：每轮问题都留在时间线上（不再被覆盖）',
  multiNames.filter((n) => n.startsWith('USER QUESTION')).length === 2,
  multiNames,
);
check('多轮：两轮各自的问题文本都在', multiActions.some((a) => a.summary.includes('第一问')) && multiActions.some((a) => a.summary.includes('第二问')));
check(
  '多轮：同名动作按轮次区分（第二轮的理解/结论不被并进第一轮）',
  multiNames.filter((n) => n === 'UNDERSTAND INCIDENT').length === 2 && multiNames.filter((n) => n === 'DIAGNOSE').length === 2,
  multiNames,
);
const q2Index = multiNames.indexOf('USER QUESTION #2');
check(
  '多轮：第二轮问题节点插在两轮之间（时间顺序）',
  q2Index > multiNames.indexOf('DIAGNOSE') && q2Index < multiNames.lastIndexOf('DIAGNOSE'),
  { q2Index, firstDiag: multiNames.indexOf('DIAGNOSE'), lastDiag: multiNames.lastIndexOf('DIAGNOSE') },
);
check(
  '多轮：单轮会话不加问题节点（避免与标题重复）',
  buildTimeline(events, false, [multiTurns[0]]).every((a) => !a.name.startsWith('USER QUESTION')),
);
check(
  '多轮：结论取最后一次完成的 summary（而不是最长的那个）',
  buildConclusion(multiTurnEvents) === secondTurnSummary,
  buildConclusion(multiTurnEvents).slice(0, 24),
);
check(
  '多轮：当前轮事件切片只含最后一次提问之后的事件',
  eventsOfCurrentTurn(multiTurnEvents, multiTurns).every((e) => e.timestamp >= multiTurns[1].at) &&
    eventsOfCurrentTurn(multiTurnEvents, multiTurns).length === turn2Events.length,
);
check('多轮：无轮次信息时退回全部事件（兼容旧数据）', eventsOfCurrentTurn(multiTurnEvents, []).length === multiTurnEvents.length);

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);