import { useState } from 'react';
import { Tooltip } from 'antd';
import type { TimelineAction } from '../utils/investigation';

const STATUS_TEXT: Record<TimelineAction['status'], string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  blocked: 'Blocked',
  failed: 'Failed',
};

const DOT_CLASS: Record<TimelineAction['status'], string> = {
  pending: 'tl-dot pending',
  running: 'tl-dot running',
  completed: 'tl-dot completed',
  blocked: 'tl-dot blocked',
  failed: 'tl-dot failed',
};

/**
 * Investigation Timeline：把 Agent 的调查过程呈现为一条结构化动作序列。
 * 每个动作严格来自真实事件；大段原文折叠在动作内（不丢信息，也不糊成一片）。
 */
export default function InvestigationTimeline({
  actions,
  activeEvidence,
  onCite,
}: {
  actions: TimelineAction[];
  activeEvidence: string | null;
  onCite: (id: string) => void;
}) {
  if (actions.length === 0) return null;

  return (
    <section className="tl">
      {actions.map((a) => (
        <ActionRow key={a.key} action={a} activeEvidence={activeEvidence} onCite={onCite} />
      ))}
    </section>
  );
}

function ActionRow({
  action,
  activeEvidence,
  onCite,
}: {
  action: TimelineAction;
  activeEvidence: string | null;
  onCite: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const hasDetail = Boolean(action.detail && action.detail.trim().length > 0);

  return (
    <div className={`tl-item${action.status === 'running' ? ' is-running' : ''}`}>
      <span className={DOT_CLASS[action.status]} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="tl-head">
          <span className="tl-name">{action.name}</span>
          {action.step != null && <span className="tl-step">Step {action.step}</span>}
          <span style={{ flex: 1 }} />
          <span className={`tl-status ${action.status}`}>{STATUS_TEXT[action.status]}</span>
        </div>

        {action.summary && <div className="tl-summary">{action.summary}</div>}

        {action.purpose && (
          <div className="tl-purpose">
            <span className="tl-purpose-label">Purpose</span>
            <span>{action.purpose}</span>
          </div>
        )}

        {(action.dataSource || action.resultText || action.durationMs != null || action.evidenceIds.length > 0) && (
          <div className="tl-meta">
            {action.dataSource && (
              <span className="tl-kv">
                <b>SOURCE</b>
                <span className="mono">{action.dataSource}</span>
              </span>
            )}
            {action.resultText && (
              <span className="tl-kv">
                <b>RESULT</b>
                <span className="mono">{action.resultText}</span>
              </span>
            )}
            {action.durationMs != null && (
              <span className="tl-kv">
                <b>TIME</b>
                <span className="mono">{action.durationMs}ms</span>
              </span>
            )}
            {action.evidenceIds.map((id) => (
              <Tooltip key={id} title={`查看证据 ${id} 的真实数据`} mouseEnterDelay={0.2}>
                <span
                  className={`cite${activeEvidence === id ? ' is-active' : ''}`}
                  onClick={() => onCite(id)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && onCite(id)}
                >
                  <span className="cite-id" style={{ borderLeft: 'none', paddingLeft: 0 }}>
                    {id}
                  </span>
                  Evidence
                </span>
              </Tooltip>
            ))}
            {action.warnings.length > 0 && <span className="tl-warn">{action.warnings[0]}</span>}
          </div>
        )}

        {hasDetail && (
          <button className="tl-toggle" onClick={() => setOpen((v) => !v)}>
            {open ? '− 收起 Agent 原文' : '+ 展开 Agent 原文'}
          </button>
        )}
        {open && hasDetail && <pre className="tl-detail">{action.detail}</pre>}
      </div>
    </div>
  );
}
