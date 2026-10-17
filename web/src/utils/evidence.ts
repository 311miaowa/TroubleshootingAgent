import type { AgentEvent, QueryResult, ToolCallEvent } from '../types/events';
import type { SourceHealth } from '../api/datasource';
import { parseResultRawRef } from './rawRef';

/** 数据源实体（来自 describe_entity 的真实返回） */
export interface SourceEntity {
  dsId: string;
  entityCode: string;
  entityName: string;
  entityDesc?: string;
  capability: string;
  physicalName?: string;
  timeField?: string;
  fields: Array<{ fieldName: string; fieldLabel?: string; fieldType?: string; sensitive?: boolean }>;
}

export type EvidenceKind = 'log' | 'table' | 'cross' | 'unknown';

/** 一条可核查的证据（= 一次真实数据查询的返回） */
export interface EvidenceItem {
  /** EV-01 */
  id: string;
  index: number;
  toolName: string;
  dsId: string;
  entityCode: string;
  /** 面向人的证据标题，如「应用日志」 */
  title: string;
  /** 证据类型标签（Inspector 的 TYPE 段） */
  typeLabel: string;
  kind: EvidenceKind;
  /** 物理定位（索引模式 / 表名） */
  target: string;
  /** 人类可读的查询条件（Inspector 的 QUERY 段） */
  conditions: string[];
  query: QueryResult;
  arguments: string;
  step: number;
  timestamp: number;
  durationMs: number;
  rowsCount: number;
  /** 机械且诚实的解读（Inspector 的 INTERPRETATION 段） */
  interpretation: string;
  rawRef: string | null;
  refSummary: string;
}

/** 从 describe_entity 事件提取数据源清单 */
export function buildSources(events: AgentEvent[]): SourceEntity[] {
  const map = new Map<string, SourceEntity>();
  for (const e of events) {
    if (e.type !== 'tool_call' || e.toolName !== 'describe_entity' || e.subType !== 'result' || !e.result) continue;
    try {
      const list = JSON.parse(e.result) as Array<Record<string, unknown>>;
      if (!Array.isArray(list)) continue;
      for (const it of list) {
        if (typeof it.dsId !== 'string' || typeof it.entityCode !== 'string') continue;
        const fields = Array.isArray(it.fields)
          ? (it.fields as Array<Record<string, unknown>>).map((f) => ({
              fieldName: String(f.fieldName ?? ''),
              fieldLabel: typeof f.fieldLabel === 'string' ? f.fieldLabel : undefined,
              fieldType: typeof f.fieldType === 'string' ? f.fieldType : undefined,
              sensitive: Boolean(f.sensitive),
            }))
          : [];
        map.set(`${it.dsId}:${it.entityCode}`, {
          dsId: it.dsId,
          entityCode: it.entityCode,
          entityName: typeof it.entityName === 'string' ? it.entityName : it.entityCode,
          entityDesc: typeof it.entityDesc === 'string' ? it.entityDesc : undefined,
          capability: typeof it.capability === 'string' ? it.capability : '',
          physicalName: typeof it.physicalName === 'string' ? it.physicalName : undefined,
          timeField: typeof it.timeField === 'string' ? it.timeField : undefined,
          fields,
        });
      }
    } catch {
      /* 忽略坏帧 */
    }
  }
  return Array.from(map.values());
}

/**
 * 合并「本次会话探查到的实体」与「健康端点报告的已注册实体」（M4.2）
 *
 * 为什么要合并：面板要能在**没发起任何调查之前**就显示"哪个数据源是坏的"
 * （M4 的验收：断开订单源 → 面板显示 Disconnected + hint）。只看 `describe_entity`
 * 的结果，页面上就永远是 0 个数据源，坏源也无从暴露。
 *
 * 规则：
 * - 探查来的实体保留全部细节（entityDesc / physicalName / fields）；
 * - 端点独有的实体补进来（只有 entityCode/entityName/capability，无字段字典）；
 * - 顺序稳定：先探查顺序，再按 dsId/entityCode 追加。
 */
export function mergeHealthSources(
  discovered: SourceEntity[],
  health: Map<string, SourceHealth> | null | undefined,
): SourceEntity[] {
  if (!health || health.size === 0) return discovered;

  const merged = [...discovered];
  const seen = new Set(discovered.map((s) => `${s.dsId}:${s.entityCode}`));
  const extra: SourceEntity[] = [];

  for (const source of health.values()) {
    for (const entity of source.entities ?? []) {
      if (!entity || typeof entity.entityCode !== 'string') continue;
      const key = `${source.dsId}:${entity.entityCode}`;
      if (seen.has(key)) continue;
      seen.add(key);
      extra.push({
        dsId: source.dsId,
        entityCode: entity.entityCode,
        entityName: entity.entityName || entity.entityCode,
        capability: entity.capability || '',
        fields: [],
      });
    }
  }

  extra.sort((a, b) => (a.dsId === b.dsId ? a.entityCode.localeCompare(b.entityCode) : a.dsId.localeCompare(b.dsId)));
  return merged.concat(extra);
}

function kindOf(capability: string | undefined, toolName: string): EvidenceKind {
  if (capability === 'log_search') return 'log';
  if (capability === 'entity_query') return 'table';
  // P1 跨源对齐：能力编码 cross_source；工具名兜底（该工具的 dsId 是合成来源，不参与能力匹配）
  if (capability === 'cross_source' || toolName.includes('correlated')) return 'cross';
  if (toolName.includes('log')) return 'log';
  if (toolName.includes('order')) return 'table';
  return 'unknown';
}

const KIND_TYPE_LABEL: Record<EvidenceKind, string> = {
  log: 'Log Search',
  table: 'Database Query',
  cross: 'Cross-Source',
  unknown: 'Data Query',
};

/** P1-2：通用过滤操作符的可读写法 */
const OP_LABEL: Record<string, string> = {
  EQ: '=',
  NE: '≠',
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
  LIKE: 'LIKE',
  IN: '∈',
};

/** 把工具入参渲染成人类可读的查询条件 */
export function humanizeArgs(args: string, source?: SourceEntity): string[] {  let o: Record<string, unknown> = {};
  try {
    o = JSON.parse(args || '{}') as Record<string, unknown>;
  } catch {
    return ['（参数解析失败）'];
  }
  const out: string[] = [];
  const push = (k: string, v: unknown) => {
    if (typeof v === 'string' && v.trim()) out.push(`${k} = "${v}"`);
    else if (typeof v === 'number') out.push(`${k} = ${v}`);
  };

  push('keyword', o.keyword);
  push('order_no', o.orderNo);
  if (typeof o.bucketMinutes === 'number') out.push(`时间桶 = ${o.bucketMinutes} 分钟`);
  // P1-2 通用过滤：把结构化条件渲染成人话（后端仍会按字段字典再校验一次）
  if (Array.isArray(o.filters)) {
    for (const item of o.filters as Array<Record<string, unknown>>) {
      if (!item || typeof item.field !== 'string') continue;
      const op = typeof item.operator === 'string' ? OP_LABEL[item.operator] ?? item.operator : '=';
      const value = item.value === undefined || item.value === null ? '' : String(item.value);
      out.push(`${item.field} ${op} ${value}`);
    }
  }
  const tf = source?.timeField ?? '@timestamp';
  if (typeof o.startTime === 'string' || typeof o.endTime === 'string') {
    const s = typeof o.startTime === 'string' ? o.startTime.slice(0, 19).replace('T', ' ') : '−∞';
    const e = typeof o.endTime === 'string' ? o.endTime.slice(0, 19).replace('T', ' ') : '+∞';
    out.push(`${tf} ∈ [${s}, ${e}]`);
  }
  push('limit', o.limit);
  if (out.length === 0) out.push('（无过滤条件，按默认时间窗检索）');
  return out;
}

/** 机械解读：只描述「数据本身说明了什么」，不替 Agent 下结论 */
function interpret(kind: EvidenceKind, rows: number, total: number, truncated: boolean, warnings: string[]): string {
  const unit = kind === 'log' ? '日志' : kind === 'table' ? '记录' : kind === 'cross' ? '时间桶' : '数据';
  if (warnings.length > 0 && rows === 0) return `查询未返回数据：${warnings[0]}`;
  if (rows === 0) return `未命中任何${unit}。该证据暂不支持当前假设。`;
  const more = truncated ? `（命中总数 ${total}，仅返回前 ${rows} 条）` : '';
  return `命中 ${total} ${unit}，本次返回 ${rows} 条${more}。可作为对应结论的数据支撑。`;
}

/**
 * 证据注册表：只收录「真实数据查询」的结果（query_*）；
 * describe_entity 属于探查（进数据源清单，不编号为证据）。
 */
export function buildEvidence(events: AgentEvent[], sources: SourceEntity[]): EvidenceItem[] {
  const items: EvidenceItem[] = [];
  for (const e of events) {
    if (e.type !== 'tool_call') continue;
    const tc = e as ToolCallEvent;
    if (tc.subType !== 'result' || !tc.result || !tc.toolName.startsWith('query_')) continue;
    let q: QueryResult;
    try {
      q = JSON.parse(tc.result) as QueryResult;
    } catch {
      continue;
    }
    const src = sources.find((s) => s.dsId === q.dsId && s.entityCode === q.entityCode);
    const parsed = parseResultRawRef(q.rawRef);
    const kind = kindOf(src?.capability, tc.toolName);
    const rows = q.rows ?? [];
    const index = items.length + 1;
    items.push({
      id: `EV-${String(index).padStart(2, '0')}`,
      index,
      toolName: tc.toolName,
      dsId: q.dsId,
      entityCode: q.entityCode,
      title: src?.entityName ?? q.entityCode ?? tc.toolName,
      typeLabel: KIND_TYPE_LABEL[kind],
      kind,
      target: src?.physicalName ?? q.entityCode ?? '—',
      conditions: humanizeArgs(tc.arguments, src),
      query: q,
      arguments: tc.arguments,
      step: tc.step,
      timestamp: tc.timestamp,
      durationMs: q.costMs,
      rowsCount: rows.length,
      interpretation: interpret(kind, rows.length, q.total, Boolean(q.truncated), q.warnings ?? []),
      rawRef: q.rawRef,
      refSummary: parsed ? `${parsed.physicalName} · ${parsed.timeRange}` : (q.rawRef ?? ''),
    });
  }
  return items;
}

/* ============================================================================
   引用锚点：把结论正文与真实证据关联起来。

   早期实现要求「整串原文逐字出现」，而真实模型会改写 / 截断日志原文，
   导致引用几乎从不触发。改为多粒度锚点：
     1) 强标识字段（order_no / account_id / trace_id / user_id …）
     2) message 按标点切分出的子句（>=8 字）
     3) 时间戳归一化变体（去 T / 去时区 / MM-DD HH:mm:ss）
   ========================================================================== */

const ID_KEYS = ['order_no', 'account_id', 'trace_id', 'user_id', 'article_id', 'session_id', 'error_code'];
const MASKED = /\*{2,}/;

function pushAnchor(out: Set<string>, v: unknown) {
  if (typeof v !== 'string') return;
  const t = v.trim();
  if (t.length < 8 || t.length > 120) return;
  if (MASKED.test(t)) return; // 脱敏值不作为锚点
  if (!/[\p{L}\p{N}]/u.test(t)) return;
  out.add(t);
}

/**
 * 证据锚点集合。
 * 刻意**不**包含时间戳与 application/service 这类通用值：
 * 它们单独出现不足以证明「这句话有数据支撑」，会把表头、泛化描述误判为 Confirmed。
 */
export function anchorsOf(ev: EvidenceItem): string[] {
  const out = new Set<string>();
  for (const row of ev.query.rows ?? []) {
    for (const k of Object.keys(row)) {
      if (k === '_rawRef') continue;
      if (ID_KEYS.includes(k)) pushAnchor(out, row[k]);
    }
    const msg = row.message ?? row.msg ?? row.fail_reason;
    if (typeof msg === 'string') {
      pushAnchor(out, msg);
      for (const clause of msg.split(/[-–—|，,；;：:]/)) {
        pushAnchor(out, clause.trim());
      }
    }
  }
  return Array.from(out);
}

/** 正文命中了哪些证据，以及命中的具体锚点（用于展示「这条结论依据哪段数据」） */
export function matchEvidenceDetail(text: string, items: EvidenceItem[]): Array<{ id: string; anchor: string }> {
  if (!text) return [];
  const hit: Array<{ id: string; anchor: string }> = [];
  for (const ev of items) {
    // 取命中的最长锚点：越长越具体，展示时更有说服力
    let best = '';
    for (const a of anchorsOf(ev)) {
      if (a.length > best.length && text.includes(a)) best = a;
    }
    if (best) hit.push({ id: ev.id, anchor: best });
  }
  return hit;
}

/** 正文命中了哪些证据（供 Diagnosis 判定「哪一句有数据支撑」） */
export function matchEvidenceIds(text: string, items: EvidenceItem[]): string[] {
  return matchEvidenceDetail(text, items).map((h) => h.id);
}
