import { useMemo, useState } from 'react';
import { Popover, Tooltip } from 'antd';
import type { AgentEvent } from '../types/events';
import type { SourceHealth } from '../api/datasource';
import type { SourceEntity } from '../utils/evidence';
import {
  DS_STATE_DOT,
  DS_STATE_TEXT,
  datasourceTipLines,
  rollupDatasourceState,
  sessionQueryNote,
  toDatasourceState,
  type DatasourceState,
} from '../utils/status';

const CAP_LABEL: Record<string, string> = {
  log_search: 'Log Search',
  entity_query: 'Database Query',
  entity_meta: 'Metadata',
};

/** 悬停说明的多行渲染（Tooltip 里的纯字符串不会换行） */
function TipLines({ lines }: { lines: string[] }) {
  return (
    <div style={{ maxWidth: 320, lineHeight: 1.65 }}>
      {lines.map((line, i) => (
        <div key={i}>{line}</div>
      ))}
    </div>
  );
}

/** 「12 秒前」这类相对时间，用于标注权威状态的检查时刻 */
function agoText(at: number | null, now: number): string {
  if (!at) return '';
  const s = Math.max(0, Math.round((now - at) / 1000));
  if (s < 5) return '刚刚';
  if (s < 60) return `${s} 秒前`;
  const m = Math.round(s / 60);
  return m < 60 ? `${m} 分钟前` : `${Math.round(m / 60)} 小时前`;
}

/**
 * Data Sources 入口（设计文档第十二节 + M4.2）：
 * 列出数据源、能力与**权威连接状态**，并支持按数据源过滤调查过程。
 *
 * 状态来源（M4 决策 4A）：
 * - 正常：来自 `GET /api/v1/datasource/health`（后端真实探活）→ Connected / Disconnected，
 *   `message` 与可操作的 `hint`（如"要注入哪个环境变量"）作为悬停说明；
 * - 端点不可用时降级为 `Unknown`，并在面板顶部说明原因——**不再**把"没查过"当作"已连接"；
 * - "本次会话查询过没有"作为**辅助信息**保留在悬停里，不参与状态判定。
 *
 * 后端暂无数据源管理 API，因此 Add / Manage 只给出配置入口说明，不做假按钮。
 */
export default function DataSourceMenu({
  sources,
  events,
  health,
  healthError,
  healthCheckedAt,
  healthCached,
  onRefreshHealth,
  activeDsId,
  onPick,
  running,
}: {
  sources: SourceEntity[];
  events: AgentEvent[];
  health: Map<string, SourceHealth> | null;
  healthError: string | null;
  healthCheckedAt: number | null;
  healthCached: boolean;
  onRefreshHealth: () => void;
  activeDsId: string | null;
  onPick: (dsId: string | null) => void;
  running: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const now = Date.now();

  const groups = useMemo(() => {
    const m = new Map<string, SourceEntity[]>();
    for (const s of sources) {
      const list = m.get(s.dsId) ?? [];
      list.push(s);
      m.set(s.dsId, list);
    }
    return Array.from(m.entries());
  }, [sources]);

  const total = sources.length;

  const stateOf = (dsId: string): DatasourceState => toDatasourceState(health?.get(dsId));

  const rollup = rollupDatasourceState(groups.map(([dsId]) => stateOf(dsId)));
  const downCount = groups.filter(([dsId]) => stateOf(dsId) === 'disconnected').length;

  const handleRefresh = () => {
    setRefreshing(true);
    try {
      onRefreshHealth();
    } finally {
      // onRefreshHealth 是"发起刷新"，这里只做短促的视觉反馈
      window.setTimeout(() => setRefreshing(false), 600);
    }
  };

  const panel = (
    <div style={{ width: 360 }}>
      <div className="ds-panel-head">
        <span className="t-label">Data Sources · {total}</span>
        <span style={{ flex: 1 }} />
        {healthCheckedAt && (
          <Tooltip
            title={`权威状态来自 GET /api/v1/datasource/health${healthCached ? '（本次命中后端缓存）' : ''}`}
            mouseEnterDelay={0.2}
          >
            <span className="t-xs t-faint" style={{ marginRight: 8 }}>
              检查于 {agoText(healthCheckedAt, now)}
            </span>
          </Tooltip>
        )}
        <button className="link-btn" onClick={handleRefresh} disabled={refreshing}>
          {refreshing ? '刷新中…' : 'Refresh'}
        </button>
        {activeDsId && (
          <button
            className="link-btn"
            style={{ marginLeft: 8 }}
            onClick={() => {
              onPick(null);
              setOpen(false);
            }}
          >
            Clear filter
          </button>
        )}
      </div>

      {healthError && (
        <div className="t-xs" style={{ padding: '0 4px 8px', lineHeight: 1.7, color: 'var(--warn, #d89614)' }}>
          健康端点不可用（{healthError}），状态暂显示为 Unknown。
        </div>
      )}

      {total === 0 && (
        <div className="empty-hint" style={{ padding: '2px 4px 10px', lineHeight: 1.7 }}>
          {running ? 'Agent 正在探查数据源…' : '尚无数据源信息。发起一次调查后，这里会列出 Agent 实际可用的数据源。'}
        </div>
      )}

      <div style={{ maxHeight: 300, overflowY: 'auto' }}>
        {groups.map(([dsId, list]) => {
          const state = stateOf(dsId);
          const h = health?.get(dsId) ?? null;
          const isOn = activeDsId === dsId;
          const tip = datasourceTipLines(state, h, sessionQueryNote(events, dsId), healthError);
          return (
            <div
              key={dsId}
              className={`ds-row hoverable${isOn ? ' is-on' : ''}`}
              onClick={() => onPick(isOn ? null : dsId)}
            >
              <Tooltip title={<TipLines lines={tip} />} mouseEnterDelay={0.2}>
                <span className={`dot ${DS_STATE_DOT[state]}`} />
              </Tooltip>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="truncate" style={{ fontSize: 12, color: isOn ? 'var(--accent)' : 'var(--text)' }}>
                  {dsId}
                </div>
                <div className="t-xs t-faint truncate">
                  {list.map((s) => `${s.entityName}（${CAP_LABEL[s.capability] ?? s.capability}）`).join(' · ')}
                </div>
                {state === 'disconnected' && h?.hint && (
                  <div className="t-xs truncate" style={{ color: 'var(--err, #ff7875)' }} title={h.hint}>
                    {h.hint}
                  </div>
                )}
              </div>
              <span className="t-xs t-faint">{DS_STATE_TEXT[state]}</span>
            </div>
          );
        })}
      </div>

      <div className="ds-panel-foot">
        <div className="t-xs t-faint" style={{ lineHeight: 1.6 }}>
          连接状态来自后端真实探活（<span className="mono">/api/v1/datasource/health</span>）；
          实体清单来自 Agent 探查（<span className="mono">describe_entity</span>）。
        </div>
        <div className="ds-panel-actions">
          <Tooltip title="新增/修改数据源需在后端 ai_data_source 等 4 张表中配置，前端暂不提供写接口" mouseEnterDelay={0.2}>
            <span className="link-btn is-muted">Add Data Source</span>
          </Tooltip>
          <Tooltip title="同上：数据源管理落在后端配置表，热更新会在 60 秒内生效" mouseEnterDelay={0.2}>
            <span className="link-btn is-muted">Manage Data Sources</span>
          </Tooltip>
        </div>
      </div>
    </div>
  );

  const entryDot = rollup === 'disconnected' ? 'err' : rollup === 'connected' ? 'ok' : running ? 'warn pulse' : 'idle';

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomLeft"
      arrow={false}
      content={panel}
      styles={{ body: { padding: 10 } }}
    >
      <button className="ds-entry">
        <span className={`dot ${entryDot}`} />
        <span>Data Sources</span>
        <span className="mono t-xs" style={{ color: 'var(--text-3)' }}>
          {total}
        </span>
        {downCount > 0 && (
          <span className="mono t-xs" style={{ color: 'var(--err, #ff7875)' }}>
            {downCount} down
          </span>
        )}
        <span style={{ color: 'var(--text-3)', fontSize: 9 }}>▾</span>
      </button>
    </Popover>
  );
}
