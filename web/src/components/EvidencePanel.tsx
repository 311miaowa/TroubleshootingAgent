import { useEffect, useMemo, useRef, useState } from 'react';
import { Tooltip, message } from 'antd';
import LogView from './evidence/LogView';
import TableView from './evidence/TableView';
import TimelineView from './evidence/TimelineView';
import type { EvidenceItem, SourceEntity } from '../utils/evidence';
import { kibanaDocUrl, parseRowRawRef } from '../utils/rawRef';

/**
 * EVIDENCE：右侧证据检查器（设计文档第七、八节）。
 * 上方是本次调查产生的证据索引；选中后在此展开 Inspector：
 * SOURCE / TYPE / QUERY / EXECUTION / RESULT / RAW DATA / INTERPRETATION。
 */
export default function EvidencePanel({
  evidence,
  sources,
  selectedId,
  onSelect,
  dsFilter,
}: {
  evidence: EvidenceItem[];
  sources: SourceEntity[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  dsFilter: string | null;
}) {
  const listRef = useRef<HTMLDivElement>(null);
  const selected = useMemo(() => evidence.find((e) => e.id === selectedId) ?? null, [evidence, selectedId]);

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [evidence.length]);

  const sourceOf = (e: EvidenceItem) => sources.find((s) => s.dsId === e.dsId && s.entityCode === e.entityCode);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minWidth: 0 }}>
      <div className="ev-index">
        <div className="ev-index-head">
          <span className="t-label">Evidence</span>
          <span className="t-xs t-faint mono">{evidence.length} sources</span>
        </div>
        {dsFilter && (
          <div className="t-xs t-faint truncate" style={{ marginBottom: 6 }}>
            filtered · <span className="mono" style={{ color: 'var(--accent)' }}>{dsFilter}</span>
          </div>
        )}
        <div ref={listRef} className="ev-list">
          {evidence.length === 0 && (
            <div className="empty-hint" style={{ padding: '6px 2px', lineHeight: 1.7 }}>
              还没有证据。Agent 每一次真实数据查询都会在这里留下可核查的记录。
            </div>
          )}
          {evidence.map((e) => {
            const isOn = e.id === selectedId;
            return (
              <div key={e.id} className={`ev-row hoverable${isOn ? ' is-active' : ''}`} onClick={() => onSelect(e.id)}>
                <span className="ev-id">{e.id}</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="truncate" style={{ fontSize: 12, color: isOn ? 'var(--text)' : 'var(--text-2)' }}>
                    {e.title}
                  </div>
                  <div className="t-xs t-faint mono truncate">
                    {e.rowsCount > 0 ? `${e.query.total} matches` : '0 matches'} ·{' '}
                    {new Date(e.timestamp).toLocaleTimeString('zh-CN', { hour12: false })}
                  </div>
                </div>
                <span className={`dot ${e.rowsCount > 0 ? 'ok' : 'idle'}`} />
              </div>
            );
          })}
        </div>
      </div>

      <div className="ev-inspector">
        {!selected ? (
          <div className="empty-hint" style={{ paddingTop: 26, lineHeight: 1.9 }}>
            Select an evidence to inspect its real data.
            <br />
            时间线上的 <span className="cite"><span className="cite-id" style={{ borderLeft: 'none', paddingLeft: 0 }}>EV-01</span></span>{' '}
            同样可以点击定位到这里。
          </div>
        ) : (
          <Inspector item={selected} source={sourceOf(selected)} sources={sources} />
        )}
      </div>
    </div>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="ins-sec">
      <div className="t-label ins-label">{label}</div>
      <div>{children}</div>
    </div>
  );
}

function Inspector({ item, source, sources }: { item: EvidenceItem; source?: SourceEntity; sources: SourceEntity[] }) {
  const [raw, setRaw] = useState(false);

  const copy = (text: string) => {
    navigator.clipboard?.writeText(text).then(
      () => message.success('已复制'),
      () => message.error('复制失败'),
    );
  };

  // 从行级 rawRef 反解出可跳转的目标（ES 才可跳 Kibana）
  const firstRowRef = (item.query.rows ?? []).map((r) => r._rawRef).find((v): v is string => typeof v === 'string');
  const parsedRow = parseRowRawRef(firstRowRef ?? null);
  const kibana =
    parsedRow.kind === 'es' && parsedRow.esIndex && parsedRow.esId
      ? kibanaDocUrl(parsedRow.esIndex, parsedRow.esId)
      : '';

  return (
    <div>
      <div className="ins-head">
        <span className="ev-id">{item.id}</span>
        <span style={{ fontSize: 13, fontWeight: 600 }}>{item.title}</span>
        <span style={{ flex: 1 }} />
        <Tooltip title="回到中间区域的 Diagnosis" mouseEnterDelay={0.25}>
          <button
            className="link-btn"
            onClick={() =>
              document.getElementById('investigation-diagnosis')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
            }
          >
            Back to diagnosis
          </button>
        </Tooltip>
      </div>

      <Section label="Source">
        <div className="ins-value mono">{item.target}</div>
        <div className="t-xs t-faint">
          {item.dsId}
          {source ? ` · ${source.entityName}` : ''}
          {source?.timeField ? ` · time field ${source.timeField}` : ''}
        </div>
      </Section>

      <Section label="Type">
        <div className="ins-value">{item.typeLabel}</div>
      </Section>

      <Section label="Query">
        {item.conditions.map((c, i) => (
          <div key={i} className="ins-value mono">
            {c}
          </div>
        ))}
      </Section>

      <Section label="Execution">
        <div className="ins-value mono">
          {new Date(item.timestamp).toLocaleTimeString('zh-CN', { hour12: false })} · {item.durationMs}ms · step{' '}
          {item.step}
        </div>
      </Section>

      <Section label="Result">
        <div className="ins-value">
          {item.kind === 'cross' ? (
            item.rowsCount > 0 ? (
              <>
                <b style={{ color: 'var(--text)' }}>{item.query.total}</b> 个时间桶
              </>
            ) : (
              '0 个时间桶'
            )
          ) : item.rowsCount > 0 ? (
            <>
              <b style={{ color: 'var(--text)' }}>{item.query.total}</b> matched
              {item.query.truncated ? `（返回前 ${item.rowsCount} 条）` : ''}
            </>
          ) : (
            '0 records'
          )}
        </div>
      </Section>

      <Section label="Raw data">
        <div className="ins-actions" style={{ marginBottom: 8 }}>
          {kibana && (
            <a className="link-btn" href={kibana} target="_blank" rel="noreferrer">
              Open source
            </a>
          )}
          <button className="link-btn" onClick={() => setRaw((v) => !v)}>
            {raw ? 'Hide raw data' : 'View raw data'}
          </button>
          {item.rawRef && (
            <button className="link-btn" onClick={() => copy(item.rawRef!)}>
              Copy reference
            </button>
          )}
          {!kibana && item.kind === 'log' && (
            <Tooltip
              title="未配置 VITE_KIBANA_BASE，无法直接跳转；可用 Copy reference 复制 索引#_id 后在 Kibana 检索"
              mouseEnterDelay={0.2}
            >
              <span className="t-xs t-faint">Kibana 未配置</span>
            </Tooltip>
          )}
        </div>

        {raw ? (
          <pre className="ins-raw mono">{JSON.stringify(item.query.rows, null, 2)}</pre>
        ) : item.kind === 'log' ? (
          <LogView item={item} />
        ) : item.kind === 'cross' ? (
          <TimelineView item={item} sources={sources} />
        ) : (
          <TableView item={item} />
        )}

        {item.rawRef && (
          <div className="t-xs t-faint mono" style={{ marginTop: 6, wordBreak: 'break-all' }}>
            {item.rawRef}
          </div>
        )}
      </Section>

      <Section label="Interpretation">
        <div className="ins-interp">{item.interpretation}</div>
        {item.query.warnings && item.query.warnings.length > 0 && (
          <div className="t-xs" style={{ marginTop: 4, color: 'var(--warn)' }}>
            {item.query.warnings.join('；')}
          </div>
        )}
      </Section>
    </div>
  );
}
