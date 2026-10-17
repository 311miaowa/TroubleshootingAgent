import { useMemo, useRef, useState } from 'react';
import { Input, Popconfirm, Tooltip } from 'antd';
import type { Session } from '../types/session';
import { STATUS_DOT, STATUS_LABEL, toStatus } from '../utils/status';

function bucketOf(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (ts >= startOfToday) return 'Today';
  if (ts >= startOfToday - 86400000) return 'Yesterday';
  if (ts >= startOfToday - 86400000 * 7) return 'Previous 7 Days';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function timeText(ts: number): string {
  const d = new Date(ts);
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const now = new Date();
  const sameDay =
    d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  return sameDay ? hm : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`;
}

/**
 * INVESTIGATIONS：左侧历史故障调查列表（设计文档第三节）。
 * 定位是「故障调查记录」而非聊天历史；视觉重量弱化，选中项用极轻背景 + 左侧指示线。
 */
export default function HistorySidebar({
  sessions,
  activeId,
  onSelect,
  onCreate,
  onDelete,
  onRename,
}: {
  sessions: Session[];
  activeId: string;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onDelete: (id: string) => void;
  onRename: (id: string, title: string) => void;
}) {
  const [kw, setKw] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const groups = useMemo(() => {
    const q = kw.trim().toLowerCase();
    const filtered = q
      ? sessions.filter(
          (s) =>
            s.title.toLowerCase().includes(q) ||
            s.question.toLowerCase().includes(q) ||
            // 多轮会话：历史轮的追问也要能搜到（它们不再被覆盖，见 types/session.ts 的 turns）
            (s.turns ?? []).some((t) => t.question.toLowerCase().includes(q)),
        )
      : sessions;
    const map = new Map<string, Session[]>();
    for (const s of filtered) {
      const b = bucketOf(s.updatedAt || s.createdAt);
      const list = map.get(b) ?? [];
      list.push(s);
      map.set(b, list);
    }
    return Array.from(map.entries());
  }, [sessions, kw]);

  const startRename = (s: Session) => {
    setEditingId(s.id);
    setDraft(s.title);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  const commitRename = () => {
    if (editingId) onRename(editingId, draft);
    setEditingId(null);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '10px 10px 8px' }}>
        <div className="side-head">
          <span className="t-label">Investigations</span>
        </div>
        <button className="new-inv" onClick={onCreate}>
          <span style={{ fontSize: 13, lineHeight: 1 }}>+</span>
          New Investigation
        </button>
        <Input
          size="small"
          allowClear
          value={kw}
          onChange={(e) => setKw(e.target.value)}
          placeholder="Search"
          style={{ fontSize: 12, marginTop: 8 }}
        />
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 6px 10px' }}>
        {groups.length === 0 && (
          <div className="empty-hint" style={{ padding: '10px 6px' }}>
            {sessions.length === 0 ? 'No investigations yet.' : 'No match.'}
          </div>
        )}

        {groups.map(([bucket, list]) => (
          <div key={bucket} style={{ marginBottom: 4 }}>
            <div className="t-xs t-faint side-bucket">{bucket}</div>
            {list.map((s) => {
              const isActive = s.id === activeId;
              const editing = editingId === s.id;
              const st = toStatus(s.status);
              return (
                <div
                  key={s.id}
                  className={`sess-row hoverable${isActive ? ' is-active' : ''}`}
                  onClick={() => !editing && onSelect(s.id)}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    {editing ? (
                      <Input
                        ref={inputRef as never}
                        size="small"
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                        onBlur={commitRename}
                        onPressEnter={commitRename}
                        onClick={(e) => e.stopPropagation()}
                        style={{ fontSize: 12, height: 22 }}
                      />
                    ) : (
                      <>
                        <div
                          className="truncate"
                          style={{ fontSize: 12, color: isActive ? 'var(--text)' : 'var(--text-2)' }}
                          title={s.title}
                        >
                          {s.title || 'Untitled investigation'}
                        </div>
                        <div className="t-xs t-faint sess-meta">
                          <span className={`dot ${STATUS_DOT[st]}`} style={{ width: 5, height: 5 }} />
                          <span>{STATUS_LABEL[st]}</span>
                          <span>·</span>
                          <span>{timeText(s.updatedAt || s.createdAt)}</span>
                        </div>
                      </>
                    )}
                  </div>

                  {!editing && (
                    <div className="hover-actions">
                      <TinyBtn
                        title="重命名"
                        glyph="✎"
                        onClick={(e) => {
                          e.stopPropagation();
                          startRename(s);
                        }}
                      />
                      <Popconfirm
                        title="删除这条调查记录？"
                        okText="删除"
                        cancelText="取消"
                        okButtonProps={{ danger: true, size: 'small' }}
                        cancelButtonProps={{ size: 'small' }}
                        onConfirm={() => onDelete(s.id)}
                      >
                        <TinyBtn title="删除" glyph="✕" onClick={(e) => e.stopPropagation()} />
                      </Popconfirm>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function TinyBtn({
  title,
  glyph,
  onClick,
}: {
  title: string;
  glyph: string;
  onClick: (e: React.MouseEvent) => void;
}) {
  return (
    <Tooltip title={title} mouseEnterDelay={0.25}>
      <button
        onClick={onClick}
        style={{
          width: 18,
          height: 18,
          border: 'none',
          borderRadius: 'var(--radius-sm)',
          background: 'transparent',
          color: 'var(--text-3)',
          cursor: 'pointer',
          fontSize: 10,
          lineHeight: 1,
        }}
      >
        {glyph}
      </button>
    </Tooltip>
  );
}
