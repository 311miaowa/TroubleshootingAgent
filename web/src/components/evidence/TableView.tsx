import { Fragment, useState, type CSSProperties } from 'react';
import { Tooltip, message } from 'antd';
import type { EvidenceItem } from '../../utils/evidence';
import { parseRowRawRef, rowRawRefLabel } from '../../utils/rawRef';

/** 结构化查询结果查看器（数据库记录）：表格 + 展开单行完整字段 */
export default function TableView({ item }: { item: EvidenceItem }) {
  const [openIdx, setOpenIdx] = useState<number | null>(null);
  const { columns, rows } = item.query;

  if (!rows || rows.length === 0) {
    return (
      <div className="empty-hint" style={{ padding: '18px 10px' }}>
        这次查询没有命中记录。
        {item.query.warnings?.length ? `（${item.query.warnings.join('；')}）` : ''}
      </div>
    );
  }

  const cols = columns && columns.length > 0 ? columns : Object.keys(rows[0]).filter((k) => k !== '_rawRef');

  return (
    <div
      style={{
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        overflow: 'auto',
        // 结果多时在**表格内部**滚动（而不是把面板撑长），并让滚轮留在表格里
        maxHeight: 420,
        overscrollBehavior: 'contain',
      }}
    >
      <table className="grid-table">
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            const open = openIdx === i;
            const rawRef = typeof row._rawRef === 'string' ? row._rawRef : '';
            const parsed = parseRowRawRef(rawRef);
            return (
              <Fragment key={i}>
                <tr
                  onClick={() => setOpenIdx(open ? null : i)}
                  style={{ cursor: 'pointer', background: open ? 'var(--bg-inset)' : undefined }}
                >
                  {cols.map((c) => (
                    <td key={c} className="mono" style={{ fontSize: 11.5 }}>
                      {c === 'phase' ? <PhaseBadge phase={String(row[c] ?? '')} /> : formatCell(row[c])}
                    </td>
                  ))}
                </tr>
                {open && (
                  <tr>
                    <td colSpan={cols.length} style={{ background: 'var(--bg-inset)', padding: 0 }}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          padding: '6px 9px',
                          borderBottom: '1px solid var(--border)',
                          fontSize: 11,
                        }}
                      >
                        <span className="t-faint">原文引用</span>
                        <span className="mono" style={{ color: 'var(--accent)' }}>
                          {rowRawRefLabel(parsed) || '—'}
                        </span>
                        <span style={{ flex: 1 }} />
                        {rawRef && (
                          <Tooltip title="复制原始引用" mouseEnterDelay={0.25}>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                navigator.clipboard?.writeText(rawRef).then(
                                  () => message.success('已复制引用'),
                                  () => message.error('复制失败'),
                                );
                              }}
                              style={{
                                background: 'none',
                                border: 'none',
                                color: 'var(--text-3)',
                                cursor: 'pointer',
                                fontSize: 11,
                                padding: 0,
                              }}
                            >
                              复制
                            </button>
                          </Tooltip>
                        )}
                      </div>
                      <div className="kv">
                        {Object.keys(row)
                          .filter((k) => k !== '_rawRef')
                          .map((k) => (
                            <div key={k} style={{ display: 'contents' }}>
                              <div className="kv-k">{k}</div>
                              <div className="kv-v">{JSON.stringify(row[k])}</div>
                            </div>
                          ))}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function formatCell(v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return JSON.stringify(v);
}

/**
 * phase 分色（M8）：`fault` 用警示色，`baseline_before` / `baseline_after` 用强调色。
 * <p>
 * 未知取值**原样显示**（不崩、不猜语义）——表格本来就是按 `columns` 通用渲染的，
 * 这里只加一层"这两类是故障期/正常期"的视觉提示。
 */
function PhaseBadge({ phase }: { phase: string }) {
  const isFault = phase === 'fault';
  const isBaseline = phase === 'baseline_before' || phase === 'baseline_after';
  const style: CSSProperties = {
    display: 'inline-block',
    padding: '0 6px',
    borderRadius: 4,
    fontSize: 10.5,
    lineHeight: '16px',
    whiteSpace: 'nowrap',
    border: `1px solid ${isFault ? 'var(--warn)' : isBaseline ? 'var(--accent-line)' : 'var(--border)'}`,
    background: isFault ? 'var(--warn-soft)' : isBaseline ? 'var(--accent-soft)' : 'transparent',
    color: isFault ? 'var(--warn)' : isBaseline ? 'var(--accent)' : undefined,
  };
  const label = isFault
    ? 'fault 故障期'
    : phase === 'baseline_before'
      ? 'baseline 前基线'
      : phase === 'baseline_after'
        ? 'baseline 后基线'
        : phase;
  return <span style={style}>{label}</span>;
}
