import { useState } from 'react';
import { Button, Input, Popover, Tooltip } from 'antd';
import type { SourceEntity } from '../utils/evidence';
import type { FaultWindowFields } from '../types/events';

/**
 * Investigation Command Bar（设计文档第十一节）：
 * 不是普通聊天输入框，而是发起 / 控制一次故障调查的命令栏。
 * 只提供**真实生效**的控件（数据源过滤、Agent、最大轮次、故障窗），不做装饰性假按钮。
 *
 * 故障窗（M8）：两个 `datetime-local` 输入框 → 转成 ISO-8601 随请求发送；
 * 不填就是"没有故障窗"，后端行为与旧版完全一致。
 */
export default function Composer({
  running,
  onSend,
  onStop,
  aiAgentId,
  onAiAgentId,
  maxStep,
  onMaxStep,
  dataSources,
  onFilterDs,
  dsFilter,
}: {
  running: boolean;
  onSend: (q: string, fault?: FaultWindowFields) => void;
  onStop: () => void;
  aiAgentId: string;
  onAiAgentId: (v: string) => void;
  maxStep: string;
  onMaxStep: (v: string) => void;
  dataSources: SourceEntity[];
  onFilterDs: (dsId: string | null) => void;
  dsFilter: string | null;
}) {
  const [value, setValue] = useState('');
  /** 故障窗：datetime-local 的本地时间字符串（如 2026-10-17T13:00） */
  const [faultStart, setFaultStart] = useState('');
  const [faultEnd, setFaultEnd] = useState('');

  /** 本地时间 → ISO-8601（UTC Z）；空值返回 undefined */
  const toIso = (local: string): string | undefined => {
    if (!local) return undefined;
    const d = new Date(local);
    return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
  };

  const send = () => {
    if (running || !value.trim()) return;
    const fault: FaultWindowFields = {};
    const start = toIso(faultStart);
    const end = toIso(faultEnd);
    if (start) fault.faultStart = start;
    if (end) fault.faultEnd = end;
    onSend(value.trim(), Object.keys(fault).length > 0 ? fault : undefined);
    setValue('');
  };

  const faultOn = Boolean(faultStart || faultEnd);
  const faultChip = (
    <Tooltip
      title={
        faultOn
          ? '已设置故障窗：Agent 会用它做「故障期 vs 对称正常基线」对比'
          : '可选：填了才会做故障期/基线对比（不填则行为与旧版一致）'
      }
      mouseEnterDelay={0.25}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span className={`dot ${faultOn ? 'ok' : 'idle'}`} />
        <span className="t-xs t-faint" style={{ whiteSpace: 'nowrap' }}>故障窗</span>
        <Input
          size="small"
          type="datetime-local"
          value={faultStart}
          onChange={(e) => setFaultStart(e.target.value)}
          style={{ width: 168, fontSize: 11 }}
          aria-label="故障窗开始时间"
        />
        <span className="t-xs t-faint">~</span>
        <Input
          size="small"
          type="datetime-local"
          value={faultEnd}
          onChange={(e) => setFaultEnd(e.target.value)}
          style={{ width: 168, fontSize: 11 }}
          aria-label="故障窗结束时间（留空=进行中）"
        />
        {faultOn && (
          <button
            className="link-btn"
            title="清除故障窗"
            onClick={() => {
              setFaultStart('');
              setFaultEnd('');
            }}
            style={{ fontSize: 11 }}
          >
            清除
          </button>
        )}
      </div>
    </Tooltip>
  );

  const settings = (
    <div style={{ width: 230, display: 'flex', flexDirection: 'column', gap: 8 }}>
      <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span className="t-xs t-faint">Agent ID</span>
        <Input
          size="small"
          value={aiAgentId}
          onChange={(e) => onAiAgentId(e.target.value)}
          style={{ width: 90, fontSize: 12 }}
        />
      </label>
      <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span className="t-xs t-faint">Max rounds</span>
        <Input
          size="small"
          value={maxStep}
          onChange={(e) => onMaxStep(e.target.value)}
          style={{ width: 90, fontSize: 12 }}
        />
      </label>
      <div className="t-xs t-faint" style={{ lineHeight: 1.6 }}>
        Agent ID 对应后端 <span className="mono">ai_agent.agent_id</span>；Max rounds 控制自动回流复核的轮次上限。
      </div>
    </div>
  );

  const dsOptions = (
    <div style={{ minWidth: 210 }}>
      <div className="ds-row hoverable" onClick={() => onFilterDs(null)}>
        <span className="dot idle" />
        <span style={{ fontSize: 12 }}>全部数据源</span>
      </div>
      {dataSources.map((s) => (
        <div
          key={`${s.dsId}:${s.entityCode}`}
          className={`ds-row hoverable${dsFilter === s.dsId ? ' is-on' : ''}`}
          onClick={() => onFilterDs(s.dsId)}
        >
          <span className="dot ok" />
          <span style={{ fontSize: 12 }}>{s.entityName}</span>
          <span className="mono t-faint t-xs">{s.dsId}</span>
        </div>
      ))}
      {dataSources.length === 0 && (
        <div className="empty-hint" style={{ padding: 6 }}>
          暂无数据源
        </div>
      )}
    </div>
  );

  return (
    <div className="composer">
      <Input.TextArea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        autoSize={{ minRows: 1, maxRows: 6 }}
        placeholder="Describe the problem you're investigating…"
        onPressEnter={(e) => {
          if (!e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
      />
      {/* M8：故障窗（可选）。独占一行，避免和下面的 chips 挤在一起 */}
      <div className="composer-bar" style={{ paddingTop: 2, paddingBottom: 2 }}>
        {faultChip}
      </div>
      <div className="composer-bar">
        <Popover content={dsOptions} trigger="click" placement="topLeft" arrow={false} styles={{ body: { padding: 6 } }}>
          <button className="composer-chip">
            <span className={`dot ${dsFilter ? 'ok' : 'idle'}`} />
            {dsFilter ?? 'Data source'}
            <span style={{ fontSize: 9, opacity: 0.6 }}>▾</span>
          </button>
        </Popover>

        <Popover content={settings} trigger="click" placement="topLeft" arrow={false} styles={{ body: { padding: 12 } }}>
          <button className="composer-chip">
            Agent {aiAgentId} · {maxStep} rounds
            <span style={{ fontSize: 9, opacity: 0.6 }}>▾</span>
          </button>
        </Popover>

        <span className="t-xs t-faint" style={{ marginLeft: 2 }}>
          Enter to send · Shift+Enter for newline
        </span>

        <span style={{ flex: 1 }} />

        {running ? (
          <Tooltip title="中止本轮调查（状态将标记为 Blocked）" mouseEnterDelay={0.25}>
            <Button size="small" onClick={onStop}>
              Stop
            </Button>
          </Tooltip>
        ) : (
          <Button size="small" type="primary" onClick={send} disabled={!value.trim()}>
            Send
          </Button>
        )}
      </div>
    </div>
  );
}
