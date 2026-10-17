/**
 * 跨源时间线模型（P1-3）：把「跨源对齐」证据的 QueryResult 行解析成可视化的双轴/多轴时间线。
 *
 * 后端（CrossSourceCorrelationService）返回的是"一行 = 一个时间桶"：
 *   columns: [bucketStart, logCount, orderCount, logSummary, orderSummary]（另含行级 _rawRef）
 *
 * 本模块只做**纯数据变换**（无 React、无 DOM），因此可以被 scripts/smoke.ts 直接覆盖：
 * 泳道识别、时间解析、轴跨度、以及"某侧 0 条 / 时间解析失败"这类必须如实说明的情况。
 *
 * 纪律：解析不出来就说出来（进 warnings），绝不猜时间、也不把"某侧 0 条"当成"无关联"。
 */

/** 时间桶在时间线上的一个点 */
export interface TimelineBucket {
  /** 桶序号（0 起，按时间升序） */
  index: number;
  /** 后端给的原始桶起点文本 */
  bucketStart: string;
  /** 解析后的时间（epoch ms）；解析失败为 null（该桶不进轴，只计入告警） */
  at: number | null;
  /** 每条泳道在这个桶里的条数：laneId → count */
  counts: Record<string, number>;
  /** 每条泳道在这个桶里的摘要：laneId → summary */
  summaries: Record<string, string>;
  /** 该桶涉及的原文引用（已按 ` | ` 拆开） */
  rawRefs: string[];
}

/** 一条泳道（一个数据侧） */
export interface TimelineLane {
  /** 后端列名前缀，如 log / order */
  id: string;
  /** 展示名，如 日志 / 支付流水 */
  label: string;
  /** 该侧在整条时间线上的总条数 */
  total: number;
}

export interface TimelineModel {
  lanes: TimelineLane[];
  buckets: TimelineBucket[];
  /** 轴起点/终点（epoch ms）；无有效桶时为 0 */
  axisStart: number;
  axisEnd: number;
  /** 轴跨度（毫秒）；单桶时退化为一个桶宽，避免除零 */
  spanMs: number;
  /** 桶宽（毫秒），来自工具入参 bucketMinutes，缺省按相邻桶间隔推断，再缺省 1 分钟 */
  bucketMs: number;
  /** 必须如实告诉使用者的情况（某侧 0 条、桶时间解析失败等） */
  warnings: string[];
}

const LANE_LABELS: Record<string, string> = {
  log: '日志',
  order: '实体',
  entity: '实体',
};

const DEFAULT_BUCKET_MS = 60_000;

/** 与后端一致：桶起点是 Asia/Shanghai 的墙上时间（无时区后缀） */
const BIZ_OFFSET = '+08:00';

/**
 * 解析桶起点：兼容后端当前输出（`2026-03-27 10:30:00`）与 ISO（带偏移/UTC）
 */
export function parseBucketTime(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') return Number.isFinite(value) && value > 0 ? value : null;
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (!text) return null;

  // 带时区信息的 ISO：直接交给 Date
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(text)) {
    const parsed = Date.parse(text);
    return Number.isNaN(parsed) ? null : parsed;
  }
  // 裸 ISO / 数据库风格：按业务时区解释（与后端 RowTimes 一致）
  const normalized = text.includes('T') ? text : text.replace(' ', 'T');
  const parsed = Date.parse(`${normalized}${BIZ_OFFSET}`);
  return Number.isNaN(parsed) ? null : parsed;
}

/**
 * 从列名识别泳道：约定 `<lane>Count` / `<lane>Summary`
 */
export function detectLaneIds(columns: string[] | null | undefined): string[] {
  const ids: string[] = [];
  for (const column of columns ?? []) {
    const m = /^([A-Za-z][A-Za-z0-9]*?)(Count|Summary)$/.exec(column);
    if (!m) continue;
    const id = m[1];
    if (!ids.includes(id)) ids.push(id);
  }
  return ids;
}

/**
 * 把跨源对齐结果解析成时间线模型（命名带 Cross 以区别于 investigation.buildTimeline）
 *
 * @param columns   QueryResult.columns
 * @param rows      QueryResult.rows（每行 = 一个时间桶）
 * @param options.bucketMinutes 工具入参里的桶粒度（分钟）
 * @param options.entityLabel   对齐的实体名（如「支付流水」），用来给实体泳道起名
 */
export function buildCrossTimeline(
  columns: string[] | null | undefined,
  rows: Array<Record<string, unknown>> | null | undefined,
  options?: { bucketMinutes?: number | null; entityLabel?: string | null },
): TimelineModel {
  const warnings: string[] = [];
  const safeRows = (rows ?? []).filter((r): r is Record<string, unknown> => !!r && typeof r === 'object');
  const laneIds = detectLaneIds(columns);

  // 泳道名：log → 日志；order/entity → 对齐的实体名（缺省"实体"）
  const lanes: TimelineLane[] = laneIds.map((id) => {
    let label = LANE_LABELS[id] ?? id;
    if ((id === 'order' || id === 'entity') && options?.entityLabel) {
      label = options.entityLabel;
    }
    return { id, label, total: 0 };
  });
  if (lanes.length === 0 && safeRows.length > 0) {
    warnings.push('未能从列名识别泳道（约定 <lane>Count / <lane>Summary），已退化为单泳道展示');
    lanes.push({ id: 'entry', label: options?.entityLabel ?? '数据', total: 0 });
  }

  const buckets: TimelineBucket[] = [];
  let unparsable = 0;
  safeRows.forEach((row, i) => {
    const at = parseBucketTime(row.bucketStart);
    if (at === null) unparsable += 1;

    const counts: Record<string, number> = {};
    const summaries: Record<string, string> = {};
    for (const lane of lanes) {
      counts[lane.id] = toCount(row[`${lane.id}Count`], lane.id === 'entry' ? 1 : 0);
      const summary = row[`${lane.id}Summary`];
      summaries[lane.id] = typeof summary === 'string' ? summary : '';
      lane.total += counts[lane.id];
    }

    const rawRef = row._rawRef;
    buckets.push({
      index: i,
      bucketStart: typeof row.bucketStart === 'string' ? row.bucketStart : String(row.bucketStart ?? ''),
      at,
      counts,
      summaries,
      rawRefs:
        typeof rawRef === 'string' && rawRef.trim()
          ? rawRef.split('|').map((s) => s.trim()).filter(Boolean)
          : [],
    });
  });

  // 轴只取能解析出时间的桶；顺序由后端保证（时间升序），这里再兜一层保险
  const timed = buckets.filter((b): b is TimelineBucket & { at: number } => b.at !== null).sort((a, b) => a.at - b.at);
  const axisStart = timed.length > 0 ? timed[0].at : 0;
  const axisEnd = timed.length > 0 ? timed[timed.length - 1].at : 0;

  const bucketMs = resolveBucketMs(options?.bucketMinutes, timed);

  if (unparsable > 0) {
    warnings.push(`有 ${unparsable} 个时间桶的时间无法解析，已排除在时间轴之外（未猜时间）`);
  }
  for (const lane of lanes) {
    if (lane.total === 0) {
      warnings.push(`${lane.label} 侧 0 条：不等于「无关联」，可能是时间窗/关键字不匹配或该侧确实无数据`);
    }
  }

  return {
    lanes,
    buckets,
    axisStart,
    axisEnd,
    spanMs: timed.length > 1 ? Math.max(axisEnd - axisStart, bucketMs) : bucketMs,
    bucketMs,
    warnings,
  };
}

function toCount(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() && !Number.isNaN(Number(value))) return Number(value);
  return fallback;
}

function resolveBucketMs(bucketMinutes: number | null | undefined, timed: Array<{ at: number }>): number {
  if (typeof bucketMinutes === 'number' && bucketMinutes > 0) {
    return bucketMinutes * 60_000;
  }
  let minDelta = Number.POSITIVE_INFINITY;
  for (let i = 1; i < timed.length; i++) {
    const delta = timed[i].at - timed[i - 1].at;
    if (delta > 0 && delta < minDelta) minDelta = delta;
  }
  return Number.isFinite(minDelta) ? minDelta : DEFAULT_BUCKET_MS;
}

/** 单个桶在轴上的横向位置（0~1，右侧留出一个桶宽，便于画"持续区间"） */
export function positionOf(model: TimelineModel, at: number | null): number {
  if (at === null) return 0;
  return Math.min(1, Math.max(0, (at - model.axisStart) / model.spanMs));
}

/** 轴的刻度标签（最多 max 个，均匀取桶） */
export function axisTicks(model: TimelineModel, max = 5): Array<{ at: number; label: string }> {
  const timed = model.buckets.filter((b): b is TimelineBucket & { at: number } => b.at !== null);
  if (timed.length === 0) return [];
  const step = Math.max(1, Math.ceil(timed.length / max));
  const ticks: Array<{ at: number; label: string }> = [];
  for (let i = 0; i < timed.length; i += step) {
    ticks.push({ at: timed[i].at, label: hhmmss(timed[i].bucketStart) });
  }
  const last = timed[timed.length - 1];
  if (ticks[ticks.length - 1]?.at !== last.at) {
    ticks.push({ at: last.at, label: hhmmss(last.bucketStart) });
  }
  return ticks;
}

/** 只取 `HH:mm:ss`（桶起点文本的尾部），轴上不需要日期前缀 */
export function hhmmss(bucketStart: string): string {
  const m = /(\d{2}:\d{2}:\d{2})/.exec(bucketStart ?? '');
  return m ? m[1] : bucketStart ?? '';
}

/** 从工具入参里取桶粒度（分钟） */
export function parseBucketMinutes(args: string | null | undefined): number | null {
  if (!args) return null;
  try {
    const o = JSON.parse(args) as { bucketMinutes?: unknown };
    return typeof o.bucketMinutes === 'number' && o.bucketMinutes > 0 ? o.bucketMinutes : null;
  } catch {
    return null;
  }
}

/** 从工具入参里取对齐的实体编码（P1-2 起支持 entityCode） */
export function parseEntityCode(args: string | null | undefined): string | null {
  if (!args) return null;
  try {
    const o = JSON.parse(args) as { entityCode?: unknown };
    return typeof o.entityCode === 'string' && o.entityCode.trim() ? o.entityCode.trim() : null;
  } catch {
    return null;
  }
}
