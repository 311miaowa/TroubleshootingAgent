/**
 * rawRef 解析（M3.3；P1 增补跨源关联，P1-2 增补 PostgreSQL）。
 *
 * 后端产出这些引用：
 *  - 结果级 rawRef：`es://<dsId>/<entity>/<索引>@<start>~<end>`、`mysql://...`、`pg://...`，
 *    以及跨源对齐的 `correlate://<日志源>+<实体源>@<start>~<end>`（P1）
 *  - 行级 _rawRef：ES 为 `<索引>#<_id>`，MySQL / PostgreSQL 为 `order_no=<值>`；
 *    跨源对齐的一行是"一个时间桶"，可能同时含两侧多条原文引用，用 ` | ` 连接
 */

export type RawRefKind = 'es' | 'mysql' | 'pg' | 'correlate' | 'multi' | 'unknown';

/** 跨源对齐行内引用的分隔符（与后端 CrossSourceCorrelationService 保持一致） */
const MULTI_SEPARATOR = ' | ';

export interface ParsedResultRawRef {
  kind: RawRefKind;
  dsId: string;
  entityCode: string;
  /** 索引模式 / 表名；跨源对齐时是"日志源+订单源" */
  physicalName: string;
  /** 时间窗（可能为空） */
  timeRange: string;
}

export interface ParsedRowRawRef {
  kind: RawRefKind;
  /** ES 索引名 */
  esIndex?: string;
  /** ES 文档 _id */
  esId?: string;
  /** MySQL 订单号 */
  orderNo?: string;
  /** 跨源对齐：一个时间桶里挂的多条原文引用 */
  parts?: string[];
}

export function parseResultRawRef(rawRef: string | null | undefined): ParsedResultRawRef | null {
  if (!rawRef) return null;
  // es:// / mysql:// / pg:// 同形：<scheme>://<dsId>/<entity>/<物理定位>@<时间窗>
  const m = rawRef.match(/^(es|mysql|pg):\/\/([^/]+)\/([^/]+)\/([^@]+)@(.*)$/);
  if (m) {
    return {
      kind: m[1] as RawRefKind,
      dsId: m[2],
      entityCode: m[3],
      physicalName: m[4],
      timeRange: m[5],
    };
  }
  // 跨源对齐：correlate://<日志源>+<实体源>@<start>~<end>
  const c = rawRef.match(/^correlate:\/\/([^@]+)@(.*)$/);
  if (c) {
    return {
      kind: 'correlate',
      dsId: 'cross_source',
      entityCode: 'timeline',
      physicalName: c[1],
      timeRange: c[2],
    };
  }
  return null;
}

export function parseRowRawRef(rawRef: string | null | undefined): ParsedRowRawRef {
  if (!rawRef) return { kind: 'unknown' };
  // 跨源对齐的桶级引用：先判复合形态，否则会被下面的 '#' 误判成单个 ES 文档
  if (rawRef.includes(MULTI_SEPARATOR)) {
    const parts = rawRef.split(MULTI_SEPARATOR).map((p) => p.trim()).filter(Boolean);
    return { kind: 'multi', parts };
  }
  if (rawRef.includes('#')) {
    const idx = rawRef.indexOf('#');
    return { kind: 'es', esIndex: rawRef.slice(0, idx), esId: rawRef.slice(idx + 1) };
  }
  if (rawRef.startsWith('order_no=')) {
    return { kind: 'mysql', orderNo: rawRef.slice('order_no='.length) };
  }
  return { kind: 'unknown' };
}
/**
 * Kibana 单文档回指链接（未配 KIBANA_BASE 时返回空串，调用方退化为复制）。
 */
export function kibanaDocUrl(index: string, id: string): string {
  const base = (import.meta.env.VITE_KIBANA_BASE as string | undefined)?.replace(/\/$/, '') ?? '';
  if (!base) return '';
  return `${base}/app/discover#/doc/${encodeURIComponent(index)}/${encodeURIComponent(id)}`;
}

/** 行级回指的可展示文本 */
export function rowRawRefLabel(row: ParsedRowRawRef): string {
  if (row.kind === 'es') return `${row.esIndex}#${row.esId}`;
  if (row.kind === 'mysql') return `order_no=${row.orderNo}`;
  // 跨源对齐的一行 = 一个时间桶，挂的是两侧多条原文引用（逐条回指请展开查看）
  if (row.kind === 'multi') return `${row.parts?.length ?? 0} 条原文引用（含日志与订单）`;
  return '';
}
