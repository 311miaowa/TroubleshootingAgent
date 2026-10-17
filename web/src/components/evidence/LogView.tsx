import { useState } from 'react';
import { Tooltip, message } from 'antd';
import type { EvidenceItem } from '../../utils/evidence';
import { kibanaDocUrl, parseRowRawRef, rowRawRefLabel } from '../../utils/rawRef';

const TS_KEYS = ['@timestamp', 'timestamp', 'time', 'create_time'];
const LEVEL_KEYS = ['level', 'log_level', 'severity'];
const MSG_KEYS = ['message', 'msg', 'error_message', 'fail_reason'];
/** 优先展示的字段顺序，其余字段在展开区按原顺序列出 */
const PRIMARY = [...TS_KEYS, ...LEVEL_KEYS, ...MSG_KEYS, 'application', 'service', 'logger'];

function pick(row: Record<string, unknown>, keys: string[]): string {
  for (const k of keys) {
    const v = row[k];
    if (typeof v === 'string' && v) return v;
    if (typeof v === 'number') return String(v);
  }
  return '';
}

function fmtTs(raw: string): string {
  if (!raw) return '—';
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 日志查看器：以日志行呈现真实日志，点击展开该条完整字段与原文引用。
 */
export default function LogView({ item }: { item: EvidenceItem }) {
  const [openIdx, setOpenIdx] = useState<number | null>(null);
  const rows = item.query.rows ?? [];

  if (rows.length === 0) {
    return (
      <div className="empty-hint" style={{ padding: '18px 10px' }}>
        这次查询没有命中日志。
        {item.query.warnings?.length ? `（${item.query.warnings.join('；')}）` : ''}
      </div>
    );
  }

  return (
    <div
      style={{
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        // 日志多时能滚（原来 overflow:hidden 会把长内容直接裁掉，看不到也滚不动）
        overflow: 'auto',
        maxHeight: 420,
        overscrollBehavior: 'contain',
      }}
    >
      <div className="log-line" style={{ background: 'var(--bg-elevated)', cursor: 'default', color: 'var(--text-3)' }}>
        <span>时间</span>
        <span>级别</span>
        <span>内容（点击展开完整记录）</span>
      </div>
      {rows.map((row, i) => {
        const level = pick(row, LEVEL_KEYS).toUpperCase();
        const open = openIdx === i;
        return (
          <div key={i}>
            <div className={`log-line${open ? ' is-open' : ''}`} onClick={() => setOpenIdx(open ? null : i)}>
              <span className="log-ts">{fmtTs(pick(row, TS_KEYS))}</span>
              <span className={`lvl lvl-${level || 'INFO'}`}>{level || '—'}</span>
              <span className="log-msg">{pick(row, MSG_KEYS) || '(无 message 字段)'}</span>
            </div>
            {open && <RecordDetail row={row} />}
          </div>
        );
      })}
    </div>
  );
}

function RecordDetail({ row }: { row: Record<string, unknown> }) {
  const rawRef = typeof row._rawRef === 'string' ? row._rawRef : '';
  const parsed = parseRowRawRef(rawRef);
  const kb = parsed.kind === 'es' && parsed.esIndex && parsed.esId ? kibanaDocUrl(parsed.esIndex, parsed.esId) : '';

  const keys = Object.keys(row).filter((k) => k !== '_rawRef');
  const ordered = [...PRIMARY.filter((k) => keys.includes(k)), ...keys.filter((k) => !PRIMARY.includes(k))];

  return (
    <div
      style={{
        background: 'var(--bg-inset)',
        borderTop: '1px solid var(--border)',
        borderBottom: '1px solid var(--border)',
        cursor: 'default',
      }}
    >
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
        <span className="mono" style={{ color: 'var(--accent)', wordBreak: 'break-all' }}>
          {rowRawRefLabel(parsed) || '—'}
        </span>
        <span style={{ flex: 1 }} />
        {kb && (
          <a href={kb} target="_blank" rel="noreferrer" className="t-xs" style={{ color: 'var(--accent)' }}>
            在 Kibana 打开
          </a>
        )}
        {rawRef && (
          <Tooltip title="复制原始引用" mouseEnterDelay={0.25}>
            <button
              onClick={() => {
                navigator.clipboard?.writeText(rawRef).then(
                  () => message.success('已复制引用'),
                  () => message.error('复制失败'),
                );
              }}
              style={{ background: 'none', border: 'none', color: 'var(--text-3)', cursor: 'pointer', fontSize: 11, padding: 0 }}
            >
              复制
            </button>
          </Tooltip>
        )}
      </div>
      <div className="kv">
        {ordered.map((k) => (
          <div key={k} style={{ display: 'contents' }}>
            <div className="kv-k">{k}</div>
            <div className="kv-v">{JSON.stringify(row[k])}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
