import { Tooltip } from 'antd';
import type { AgentEvent } from '../types/events';
import type { RunStatus } from '../types/session';
import type { SourceHealth } from '../api/datasource';
import type { SourceEntity } from '../utils/evidence';
import DataSourceMenu from './DataSourceMenu';
import { STATUS_DOT, STATUS_LABEL, toStatus } from '../utils/status';

/** 产品品牌区（设计文档第二节）：统一为 Troubleshooting Agent */
function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark">⌖</span>
      <div style={{ lineHeight: 1.18 }}>
        <div className="brand-name">Troubleshooting Agent</div>
        <div className="brand-sub">AI-powered troubleshooting</div>
      </div>
    </div>
  );
}

export default function TopBar({
  sources,
  events,
  health,
  healthError,
  healthCheckedAt,
  healthCached,
  onRefreshHealth,
  activeDsId,
  onPickDs,
  status,
  error,
  leftCollapsed,
  rightCollapsed,
  onToggleLeft,
  onToggleRight,
}: {
  sources: SourceEntity[];
  events: AgentEvent[];
  health: Map<string, SourceHealth> | null;
  healthError: string | null;
  healthCheckedAt: number | null;
  healthCached: boolean;
  onRefreshHealth: () => void;
  activeDsId: string | null;
  onPickDs: (dsId: string | null) => void;
  status: RunStatus;
  error: string | null;
  leftCollapsed: boolean;
  rightCollapsed: boolean;
  onToggleLeft: () => void;
  onToggleRight: () => void;
}) {
  const st = toStatus(status);
  const running = st === 'running';

  return (
    <header className="topbar">
      <IconToggle
        on={!leftCollapsed}
        title={leftCollapsed ? '展开 Investigations' : '折叠 Investigations'}
        onClick={onToggleLeft}
        glyph="▤"
      />

      <Brand />

      <div className="topbar-sep" />

      <DataSourceMenu
        sources={sources}
        events={events}
        health={health}
        healthError={healthError}
        healthCheckedAt={healthCheckedAt}
        healthCached={healthCached}
        onRefreshHealth={onRefreshHealth}
        activeDsId={activeDsId}
        onPick={onPickDs}
        running={running}
      />

      <div style={{ flex: 1 }} />

      <Tooltip title={error ?? undefined} mouseEnterDelay={0.2}>
        <span className="topbar-status">
          <span className={`dot ${STATUS_DOT[st]}`} />
          {STATUS_LABEL[st]}
        </span>
      </Tooltip>

      <div className="topbar-sep" />

      <IconToggle
        on={!rightCollapsed}
        title={rightCollapsed ? '展开 Evidence' : '折叠 Evidence'}
        onClick={onToggleRight}
        glyph="▥"
      />
    </header>
  );
}

function IconToggle({
  on,
  title,
  onClick,
  glyph,
}: {
  on: boolean;
  title: string;
  onClick: () => void;
  glyph: string;
}) {
  return (
    <Tooltip title={title} mouseEnterDelay={0.2}>
      <button className="icon-toggle" data-on={on} onClick={onClick}>
        {glyph}
      </button>
    </Tooltip>
  );
}
