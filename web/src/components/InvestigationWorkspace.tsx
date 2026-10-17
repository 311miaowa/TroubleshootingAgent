import { useEffect, useRef } from 'react';
import { Tooltip } from 'antd';
import Composer from './Composer';
import DiagnosisPanel from './DiagnosisPanel';
import InvestigationTimeline from './InvestigationTimeline';
import type { Diagnosis, TimelineAction } from '../utils/investigation';
import type { SourceEntity } from '../utils/evidence';
import type { RunStatus, Session } from '../types/session';
import type { FaultWindowFields } from '../types/events';
import { STATUS_DOT, STATUS_LABEL, toStatus } from '../utils/status';

const EXAMPLES = [
  '为什么今天 20:30 左右订单接口大量失败？',
  '最近频繁出现 API 限流报错，根因是什么？',
  '日志里的 ERROR 集中出现在哪个应用？',
];

/**
 * INVESTIGATION：中间主工作区。
 * 承担「正在调查什么」+ 调查过程（Timeline）；结论由独立 DIAGNOSIS 区承担。
 */
export default function InvestigationWorkspace({
  session,
  actions,
  diagnosis,
  evidenceCount,
  sources,
  durationMs,
  dsFilter,
  onCite,
  activeCite,
  onSend,
  onStop,
  onClearFilter,
  onFilterDs,
  aiAgentId,
  onAiAgentId,
  maxStep,
  onMaxStep,
}: {
  session: Session | null;
  actions: TimelineAction[];
  diagnosis: Diagnosis | null;
  evidenceCount: number;
  sources: SourceEntity[];
  durationMs: number | null;
  dsFilter: string | null;
  onCite: (id: string) => void;
  activeCite: string | null;
  onSend: (q: string, fault?: FaultWindowFields) => void;
  onStop: () => void;
  onClearFilter: () => void;
  onFilterDs: (dsId: string | null) => void;
  aiAgentId: string;
  onAiAgentId: (v: string) => void;
  maxStep: string;
  onMaxStep: (v: string) => void;
}) {
  const runStatus: RunStatus = session?.status ?? 'idle';
  const status = toStatus(runStatus);
  const running = status === 'running';
  const started = actions.length > 0 || !!diagnosis || !!session?.question;

  const scrollRef = useRef<HTMLDivElement>(null);
  // 流式进行中自动跟随（此前需手动往下拖）
  useEffect(() => {
    if (!running) return;
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [running, actions.length]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minWidth: 0 }}>
      <div ref={scrollRef} className="inv-scroll">
        <div className="inv-inner">
          {!started && <EmptyState running={running} sources={sources} onSend={onSend} />}

          {started && (
            <>
              <div className="inv-head">
                <div className="t-label">Investigation</div>
                <h1 className="inv-title">{session?.question || '未命名调查'}</h1>
                <div className="inv-sub">
                  <span className={`dot ${STATUS_DOT[status]}`} />
                  <span className={`inv-status ${status}`}>{STATUS_LABEL[status]}</span>
                  <span className="inv-sep">·</span>
                  <span>Data Sources · {sources.length}</span>
                  <span className="inv-sep">·</span>
                  <span>Evidence · {evidenceCount}</span>
                  {durationMs != null && (
                    <>
                      <span className="inv-sep">·</span>
                      <span>{(durationMs / 1000).toFixed(1)}s</span>
                    </>
                  )}
                </div>
                {session?.error && <div className="inv-error">{session.error}</div>}
                {dsFilter && (
                  <div className="inv-filter">
                    <span className="t-faint">Filtered by data source</span>
                    <span className="mono" style={{ color: 'var(--accent)' }}>
                      {dsFilter}
                    </span>
                    <button onClick={onClearFilter}>Clear</button>
                  </div>
                )}
              </div>

              <InvestigationTimeline actions={actions} activeEvidence={activeCite} onCite={onCite} />
              <DiagnosisPanel diagnosis={diagnosis} activeEvidence={activeCite} onCite={onCite} />

              {running && (
                <div className="inv-running">
                  <span className="dot warn pulse" />
                  Agent 正在继续调查…
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <div className="inv-composer">
        <div className="inv-inner">
          <Composer
            running={running}
            onSend={onSend}
            onStop={onStop}
            aiAgentId={aiAgentId}
            onAiAgentId={onAiAgentId}
            maxStep={maxStep}
            onMaxStep={onMaxStep}
            dataSources={sources}
            onFilterDs={onFilterDs}
            dsFilter={dsFilter}
          />
        </div>
      </div>
    </div>
  );
}

function EmptyState({
  running,
  sources,
  onSend,
}: {
  running: boolean;
  sources: SourceEntity[];
  onSend: (q: string, fault?: FaultWindowFields) => void;
}) {
  return (
    <div style={{ paddingTop: 36 }}>
      <div className="t-label">Investigation</div>
      <h1 className="inv-title" style={{ fontSize: 19, marginTop: 6 }}>
        Describe the problem you&apos;re investigating
      </h1>
      <p className="t-dim" style={{ fontSize: 12.5, margin: '0 0 20px', maxWidth: 620 }}>
        描述故障现象后，Agent 会基于已接入的数据源自主调查，并把每一步查询的真实数据留作可核查的证据。
        {running && ' 正在接入…'}
      </p>

      <div className="t-label" style={{ marginBottom: 8 }}>
        Data Sources · {sources.length}
      </div>
      {sources.length === 0 ? (
        <div className="empty-hint" style={{ marginBottom: 20 }}>
          尚无数据源信息。发起一次调查后，这里会列出 Agent 实际可用的数据源。
        </div>
      ) : (
        <div className="empty-sources">
          {sources.map((s) => (
            <span key={`${s.dsId}:${s.entityCode}`} className="empty-source">
              <span className="dot ok" />
              {s.entityName}
              <span className="mono t-faint">{s.dsId}</span>
            </span>
          ))}
        </div>
      )}

      <div className="t-label" style={{ margin: '18px 0 8px' }}>
        Try one
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxWidth: 620 }}>
        {EXAMPLES.map((ex) => (
          <Tooltip key={ex} title="点击立即发起调查" mouseEnterDelay={0.3}>
            <button className="hoverable empty-example" onClick={() => onSend(ex)}>
              {ex}
            </button>
          </Tooltip>
        ))}
      </div>
    </div>
  );
}
