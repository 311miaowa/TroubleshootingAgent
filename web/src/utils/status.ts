import type { RunStatus } from '../types/session';
import type { AgentEvent } from '../types/events';
import type { SourceHealth } from '../api/datasource';

/** 产品统一状态词（设计文档要求：Running / Completed / Blocked / Failed / Pending） */
export type InvestigationStatus = 'pending' | 'running' | 'completed' | 'blocked' | 'failed';

export const STATUS_LABEL: Record<InvestigationStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  blocked: 'Blocked',
  failed: 'Failed',
};

/** 状态指示点：绿=Completed、黄=Running/Blocked、红=Failed、灰=Pending */
export const STATUS_DOT: Record<InvestigationStatus, string> = {
  pending: 'idle',
  running: 'warn pulse',
  completed: 'ok',
  blocked: 'warn',
  failed: 'err',
};

export function toStatus(run: RunStatus): InvestigationStatus {
  switch (run) {
    case 'idle':
      return 'pending';
    case 'connecting':
    case 'streaming':
      return 'running';
    case 'done':
      return 'completed';
    case 'blocked':
      return 'blocked';
    case 'error':
      return 'failed';
    default:
      return 'pending';
  }
}

export function isActive(run: RunStatus): boolean {
  return run === 'connecting' || run === 'streaming';
}

/* ------------------------------------------------------------------ *
 * 数据源状态（M4.2）
 *
 * 决策 4A：后端的三态（healthy / message / hint）在 UI 收敛为两态展示
 * —— `healthy → Connected`、`!healthy → Disconnected`，并把 message/hint 作为悬停说明；
 * 拿不到权威状态时用第三态 `Unknown`（**不再**把"没查过"当作"已连接"，那是 M4 之前的误导）。
 * ------------------------------------------------------------------ */

export type DatasourceState = 'connected' | 'disconnected' | 'unknown';

export const DS_STATE_TEXT: Record<DatasourceState, string> = {
  connected: 'Connected',
  disconnected: 'Disconnected',
  unknown: 'Unknown',
};

export const DS_STATE_DOT: Record<DatasourceState, string> = {
  connected: 'ok',
  disconnected: 'err',
  unknown: 'idle',
};

/** 权威三态 → UI 两态（+ 未知） */
export function toDatasourceState(health?: SourceHealth | null): DatasourceState {
  if (!health) return 'unknown';
  return health.healthy ? 'connected' : 'disconnected';
}

/**
 * 面板入口按钮的汇总状态：只要有源不可用就显示异常（哪怕其它源正常），
 * 全绿才显示正常，其余为未知。
 */
export function rollupDatasourceState(states: DatasourceState[]): DatasourceState {
  if (states.length === 0) return 'unknown';
  if (states.some((s) => s === 'disconnected')) return 'disconnected';
  if (states.every((s) => s === 'connected')) return 'connected';
  return 'unknown';
}

/**
 * 悬停说明（决策 4A：message / hint 作悬停说明，并保留"本次会话查过没有"这一独立事实）
 *
 * @param state       权威状态
 * @param health      后端返回的健康快照（可能为 null）
 * @param sessionNote 本次会话的辅助信息（如"最近一次查询 14:32 · 有告警"）
 * @param endpointErr 健康端点本身不可用时的原因（此时 state 必为 unknown）
 */
export function datasourceTipLines(
  state: DatasourceState,
  health?: SourceHealth | null,
  sessionNote?: string | null,
  endpointErr?: string | null,
): string[] {
  const lines: string[] = [];

  if (state === 'connected') {
    lines.push(health?.costMs ? `探活通过：连接正常（${health.costMs}ms）` : '探活通过：连接正常');
  } else if (state === 'disconnected') {
    lines.push(health?.costMs ? `探活失败：连接不可用（${health.costMs}ms）` : '探活失败：连接不可用');
  } else {
    lines.push(endpointErr ? `未取得权威状态：${endpointErr}` : '未取得权威状态');
  }

  if (health?.dsType) lines.push(`类型：${health.dsType}`);
  if (health?.message) lines.push(`原因：${health.message}`);
  if (health?.hint) lines.push(`建议：${health.hint}`);
  if (sessionNote) lines.push(`本次会话：${sessionNote}`);

  return lines;
}

/** `HH:MM:SS`（本地时钟，用于悬停里标注"最近一次查询"） */
function clock(at: number): string {
  const d = new Date(at);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 本次会话是否查过该数据源、结果如何——**辅助信息**，与权威探活状态并存（决策 4A）
 *
 * 它回答的是另一个问题："这条数据源本次会话真的用过吗、用出问题了吗"，
 * 不能替代探活（查询成功只说明那一次拿到了数据，查询失败也可能只是条件不对）。
 */
export function sessionQueryNote(events: AgentEvent[], dsId: string): string | null {
  let last: { at: number; ok: boolean } | null = null;
  for (const e of events) {
    if (e.type !== 'tool_call' || e.subType !== 'result' || !e.result || e.dsId !== dsId) continue;
    let ok = true;
    try {
      const q = JSON.parse(e.result) as { warnings?: string[] };
      ok = !q.warnings || q.warnings.length === 0;
    } catch {
      ok = false;
    }
    last = { at: e.timestamp, ok };
  }
  if (!last) return '尚未查询';
  return `最近一次查询 ${clock(last.at)} · ${last.ok ? '成功' : '返回告警'}`;
}

