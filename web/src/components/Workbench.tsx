import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import type { FaultWindowFields } from '../types/events';
import EvidencePanel from './EvidencePanel';
import HistorySidebar from './HistorySidebar';
import InvestigationWorkspace from './InvestigationWorkspace';
import TopBar from './TopBar';
import { useSessions } from '../store/useSessions';
import { fetchDatasourceHealth, type SourceHealth } from '../api/datasource';
import { buildEvidence, buildSources, mergeHealthSources } from '../utils/evidence';
import { buildConclusion, buildDiagnosis, buildTimeline, eventsOfCurrentTurn, runDuration } from '../utils/investigation';
import { isActive } from '../utils/status';

const MIN = 200;
const MAX = 620;

/**
 * 健康状态轮询间隔：后端缓存 TTL 是 30s（`xfg.datasource.health.ttl-ms`），
 * 前端同频轮询即可——比这更密只会命中缓存，徒增请求。
 */
const HEALTH_POLL_MS = 30_000;

/**
 * 三栏工作台外壳：
 * 左 INVESTIGATIONS ｜ 中 INVESTIGATION ｜ 右 EVIDENCE
 * 区域层级靠背景明度差 + 1px 分割线建立，不靠卡片堆叠。
 */
export default function Workbench() {
  const store = useSessions();
  const { sessions, active } = store;

  const [leftW, setLeftW] = useState(252);
  const [rightW, setRightW] = useState(360);
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [dsFilter, setDsFilter] = useState<string | null>(null);
  const [selectedEv, setSelectedEv] = useState<string | null>(null);
  const [pinned, setPinned] = useState(false);
  const [aiAgentId, setAiAgentId] = useState('4');
  const [maxStep, setMaxStep] = useState('3');
  const dragRef = useRef<{ side: 'left' | 'right'; startX: number; startW: number } | null>(null);

  // ---- 数据源权威健康状态（M4.2：来自后端探活端点，不再从查询结果反推）----
  const [health, setHealth] = useState<Map<string, SourceHealth> | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [healthCheckedAt, setHealthCheckedAt] = useState<number | null>(null);
  const [healthCached, setHealthCached] = useState(false);

  const loadHealth = useCallback(async (refresh = false) => {
    try {
      const report = await fetchDatasourceHealth(refresh);
      const map = new Map<string, SourceHealth>();
      for (const s of report.sources ?? []) map.set(s.dsId, s);
      setHealth(map);
      setHealthCheckedAt(report.checkedAt ?? Date.now());
      setHealthCached(Boolean(report.cached));
      setHealthError(null);
    } catch (e) {
      // 端点不可用：状态退回 Unknown（不伪装成已连接），并在面板里说明原因
      setHealth(null);
      setHealthCheckedAt(null);
      setHealthCached(false);
      setHealthError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  // 进入页面拉一次 + 定时轮询；Refresh 按钮走 refresh=true 强制重查
  useEffect(() => {
    void loadHealth();
    const timer = window.setInterval(() => void loadHealth(), HEALTH_POLL_MS);
    return () => window.clearInterval(timer);
  }, [loadHealth]);

  const refreshHealth = useCallback(() => {
    void loadHealth(true);
  }, [loadHealth]);

  // ---- 派生数据（全部来自真实事件）----
  const allEvents = useMemo(() => sessions.flatMap((s) => s.events), [sessions]);
  const discoveredSources = useMemo(() => buildSources(allEvents), [allEvents]);
  // 面板清单 = 会话探查到的实体 ∪ 端点报告的已注册实体（后者让"没发起调查也能看到坏源"）
  const globalSources = useMemo(() => mergeHealthSources(discoveredSources, health), [discoveredSources, health]);
  const sessionSources = useMemo(() => buildSources(active?.events ?? []), [active]);
  const allEvidence = useMemo(() => buildEvidence(active?.events ?? [], sessionSources), [active, sessionSources]);
  const evidence = useMemo(
    () => (dsFilter ? allEvidence.filter((e) => e.dsId === dsFilter) : allEvidence),
    [allEvidence, dsFilter],
  );
  const running = isActive(active?.status ?? 'idle');
  // 会话是**追加式**的：事件、证据、时间线看全会话（可回看）；
  // 结论/诊断/耗时只看**当前轮**（最后一次提问之后），否则会被上一轮的长回答带偏。
  const turns = useMemo(() => active?.turns ?? [], [active]);
  const turnEvents = useMemo(() => eventsOfCurrentTurn(active?.events ?? [], turns), [active, turns]);
  const actions = useMemo(() => buildTimeline(active?.events ?? [], running, turns), [active, running, turns]);
  const conclusion = useMemo(() => buildConclusion(turnEvents), [turnEvents]);
  const diagnosis = useMemo(
    () => buildDiagnosis(turnEvents, allEvidence, conclusion, running),
    [turnEvents, allEvidence, conclusion, running],
  );
  const duration = useMemo(() => runDuration(turnEvents), [turnEvents]);

  // 切换会话：重置过滤/选中
  useEffect(() => {
    setDsFilter(null);
    setSelectedEv(null);
    setPinned(false);
    if (active?.aiAgentId) setAiAgentId(active.aiAgentId);
  }, [active?.id, active?.aiAgentId]);

  // 未手动固定选择时，自动跟随**最新**证据（此前会停在最早一条，与滚动位置错位）
  useEffect(() => {
    if (evidence.length === 0) {
      if (selectedEv) setSelectedEv(null);
      return;
    }
    if (!pinned) {
      const latest = evidence[evidence.length - 1].id;
      if (latest !== selectedEv) setSelectedEv(latest);
      return;
    }
    if (!selectedEv || !evidence.some((e) => e.id === selectedEv)) {
      setSelectedEv(evidence[evidence.length - 1].id);
    }
  }, [evidence, selectedEv, pinned]);

  const handleCite = useCallback(
    (id: string) => {
      const target = allEvidence.find((e) => e.id === id);
      if (!target) return;
      if (dsFilter && target.dsId !== dsFilter) setDsFilter(null);
      setRightCollapsed(false);
      setPinned(true);
      setSelectedEv(id);
    },
    [allEvidence, dsFilter],
  );

  const handleSelectEvidence = useCallback((id: string) => {
    setPinned(true);
    setSelectedEv(id);
  }, []);

  const handleSend = useCallback(
    (q: string, fault?: FaultWindowFields) => {
      let id = active?.id;
      if (!id) id = store.createSession(aiAgentId);
      setSelectedEv(null);
      setPinned(false);
      store.run(id, q, aiAgentId, Number(maxStep) || 3, fault);
    },
    [active?.id, aiAgentId, maxStep, store],
  );

  // ---- 拖拽调宽 ----
  const onDragStart = (side: 'left' | 'right') => (e: ReactMouseEvent) => {
    e.preventDefault();
    dragRef.current = { side, startX: e.clientX, startW: side === 'left' ? leftW : rightW };
    setDragging(true);
    const setWidth = (w: number, side2: 'left' | 'right') => (side2 === 'left' ? setLeftW(w) : setRightW(w));
    const move = (ev: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      const delta = (ev.clientX - d.startX) * (d.side === 'left' ? 1 : -1);
      setWidth(Math.min(MAX, Math.max(MIN, d.startW + delta)), d.side);
    };
    const up = () => {
      dragRef.current = null;
      setDragging(false);
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  };

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <TopBar
        sources={globalSources}
        events={allEvents}
        health={health}
        healthError={healthError}
        healthCheckedAt={healthCheckedAt}
        healthCached={healthCached}
        onRefreshHealth={refreshHealth}
        activeDsId={dsFilter}
        onPickDs={setDsFilter}
        status={active?.status ?? 'idle'}
        error={active?.error ?? null}
        leftCollapsed={leftCollapsed}
        rightCollapsed={rightCollapsed}
        onToggleLeft={() => setLeftCollapsed((v) => !v)}
        onToggleRight={() => setRightCollapsed((v) => !v)}
      />

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        {!leftCollapsed && (
          <>
            <aside className="pane pane-left" style={{ width: leftW }}>
              <HistorySidebar
                sessions={sessions}
                activeId={active?.id ?? ''}
                onSelect={store.setActiveId}
                onCreate={() => store.createSession(aiAgentId)}
                onDelete={store.deleteSession}
                onRename={store.renameSession}
              />
            </aside>
            <div
              className={`resizer${dragging && dragRef.current?.side === 'left' ? ' is-dragging' : ''}`}
              onMouseDown={onDragStart('left')}
            />
          </>
        )}

        <main className="pane-center">
          <InvestigationWorkspace
            session={active}
            actions={actions}
            diagnosis={diagnosis}
            evidenceCount={evidence.length}
            sources={sessionSources}
            durationMs={duration}
            dsFilter={dsFilter}
            onCite={handleCite}
            activeCite={selectedEv}
            onSend={handleSend}
            onStop={() => active && store.stop(active.id)}
            onClearFilter={() => setDsFilter(null)}
            onFilterDs={(dsId) => setDsFilter(dsId)}
            aiAgentId={aiAgentId}
            onAiAgentId={setAiAgentId}
            maxStep={maxStep}
            onMaxStep={setMaxStep}
          />
        </main>

        {!rightCollapsed && (
          <>
            <div
              className={`resizer${dragging && dragRef.current?.side === 'right' ? ' is-dragging' : ''}`}
              onMouseDown={onDragStart('right')}
            />
            <aside className="pane pane-right" style={{ width: rightW }}>
              <EvidencePanel
                evidence={evidence}
                sources={sessionSources}
                selectedId={selectedEv}
                onSelect={handleSelectEvidence}
                dsFilter={dsFilter}
              />
            </aside>
          </>
        )}
      </div>
    </div>
  );
}
