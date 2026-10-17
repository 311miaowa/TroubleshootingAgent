import { Fragment, useMemo, useState } from 'react';
import { Tooltip, message } from 'antd';
import type { EvidenceItem, SourceEntity } from '../../utils/evidence';
import { kibanaDocUrl, parseResultRawRef, parseRowRawRef } from '../../utils/rawRef';
import {
  axisTicks,
  buildCrossTimeline,
  hhmmss,
  parseBucketMinutes,
  parseEntityCode,
  positionOf,
  type TimelineBucket,
} from '../../utils/timeline';

/**
 * 跨源时间线查看器（P1-3）：把「跨源对齐」证据从表格升级成**一眼可见的双轴时间轴**。
 *
 * 后端给的是一行一个时间桶（bucketStart / logCount / orderCount / logSummary / orderSummary）。
 * 这里把它画成：横轴 = 时间，纵轴 = 泳道（日志 / 实体侧），
 * 每个时间桶画一条**贯穿所有泳道的竖线**——两侧都有的桶高亮，于是"同一分钟里发生了什么"一眼可见。
 *
 * 三个纪律（沿用 M3/M4）：
 * 1. 只呈现机械对齐的事实，不做因果判断；
 * 2. 某侧 0 条要**明说**（不等于"无关联"）；
 * 3. 解析不出时间的桶不猜时间，排除在轴外并计数告警。
 */
export default function TimelineView({ item, sources }: { item: EvidenceItem; sources: SourceEntity[] }) {
  const bucketMinutes = parseBucketMinutes(item.arguments);
  const entityCode = parseEntityCode(item.arguments);
  const entityLabel = entityCode
    ? (sources.find((s) => s.entityCode === entityCode)?.entityName ?? entityCode)
    : null;

  const model = useMemo(
    () => buildCrossTimeline(item.query.columns, item.query.rows, { bucketMinutes, entityLabel }),
    [item, bucketMinutes, entityLabel],
  );
  const [openIdx, setOpenIdx] = useState<number | null>(null);

  if (model.buckets.length === 0) {
    return (
      <div className="empty-hint" style={{ padding: '18px 10px' }}>
        这次跨源对齐没有产生时间桶。
        {item.query.warnings?.length ? `（${item.query.warnings.join('；')}）` : ''}
      </div>
    );
  }

  const aligned = parseResultRawRef(item.rawRef);
  const alignedText = aligned?.kind === 'correlate' ? aligned.physicalName.replace('+', '  ⇄  ') : item.dsId;
  const ticks = axisTicks(model, 5);
  const openBucket = openIdx === null ? null : model.buckets.find((b) => b.index === openIdx) ?? null;

  const isCoincidence = (b: TimelineBucket) =>
    model.lanes.filter((lane) => (b.counts[lane.id] ?? 0) > 0).length > 1;

  return (
    <div className="tl-wrap">
      <div className="tl-head">
        <span className="t-xs t-faint">对齐来源</span>
        <span className="mono" style={{ color: 'var(--accent)' }}>{alignedText}</span>
      </div>
      <div className="t-xs t-faint" style={{ marginBottom: 8 }}>
        桶粒度 {Math.round(model.bucketMs / 60000)} 分钟 · {model.buckets.length} 个时间桶 ·{' '}
        {model.lanes.map((l) => `${l.label} ${l.total} 条`).join(' / ')}
      </div>

      {/* 时间轴刻度 */}
      <div className="tl-axis">
        {ticks.map((tick) => (
          <span
            key={`${tick.at}-${tick.label}`}
            className="tl-tick mono"
            style={{ left: `${positionOf(model, tick.at) * 100}%` }}
          >
            {tick.label}
          </span>
        ))}
      </div>

      <div className="tl-body">
        {/* 贯穿所有泳道的时间切片：两侧都有的桶高亮，"对齐"因此一眼可见 */}
        <div className="tl-guides" aria-hidden>
          {model.buckets.map((b) =>
            b.at === null ? null : (
              <span
                key={b.index}
                className="tl-guide"
                data-both={isCoincidence(b)}
                style={{ left: `${positionOf(model, b.at) * 100}%` }}
              />
            ),
          )}
        </div>

        {model.lanes.map((lane) => (
          <div key={lane.id} className="tl-lane">
            <div className="tl-lane-label">
              <span className="truncate">{lane.label}</span>
              <span className="t-xs t-faint mono">{lane.total}</span>
            </div>
            <div className="tl-track">
              {model.buckets.map((b) => {
                const count = b.counts[lane.id] ?? 0;
                if (b.at === null || count === 0) return null;
                const both = isCoincidence(b);
                return (
                  <Tooltip
                    key={b.index}
                    mouseEnterDelay={0.15}
                    title={
                      <div style={{ maxWidth: 280, lineHeight: 1.6 }}>
                        <div className="mono">{b.bucketStart}</div>
                        {model.lanes.map((l) => (
                          <div key={l.id}>
                            {l.label}：{b.counts[l.id] ?? 0} 条
                          </div>
                        ))}
                        {both && <div style={{ color: 'var(--accent)' }}>该桶内两侧都有数据（时间上吻合）</div>}
                      </div>
                    }
                  >
                    <button
                      className="tl-marker"
                      data-both={both}
                      data-open={openIdx === b.index}
                      style={{ left: `${positionOf(model, b.at) * 100}%` }}
                      onClick={() => setOpenIdx(openIdx === b.index ? null : b.index)}
                    >
                      {count > 1 ? count : ''}
                    </button>
                  </Tooltip>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      {openBucket ? (
        <div className="tl-detail">
          <div className="tl-detail-head">
            <span className="mono">{openBucket.bucketStart}</span>
            <span className="t-xs t-faint">
              {isCoincidence(openBucket) ? '两侧都有数据（时间上吻合，不代表因果）' : '仅单侧有数据'}
            </span>
            <span style={{ flex: 1 }} />
            <button className="link-btn" onClick={() => setOpenIdx(null)}>
              收起
            </button>
          </div>

          {model.lanes.map((lane) => {
            const count = openBucket.counts[lane.id] ?? 0;
            const summary = openBucket.summaries[lane.id];
            return (
              <Fragment key={lane.id}>
                <div className="tl-detail-row">
                  <span className="tl-detail-lane">{lane.label}</span>
                  <span className="mono t-xs t-faint">{count} 条</span>
                  <span className="tl-detail-summary" title={summary || undefined}>
                    {count === 0 ? '—' : summary || '（无摘要）'}
                  </span>
                </div>
              </Fragment>
            );
          })}

          <div className="tl-refs">
            <span className="t-xs t-faint">原文引用</span>
            {openBucket.rawRefs.length === 0 ? (
              <span className="t-xs t-faint">—</span>
            ) : (
              openBucket.rawRefs.map((ref) => {
                const parsed = parseRowRawRef(ref);
                const kibana =
                  parsed.kind === 'es' && parsed.esIndex && parsed.esId
                    ? kibanaDocUrl(parsed.esIndex, parsed.esId)
                    : '';
                return (
                  <span key={ref} className="tl-ref">
                    <span className="mono">{ref}</span>
                    {kibana && (
                      <a className="link-btn" href={kibana} target="_blank" rel="noreferrer">
                        打开原文
                      </a>
                    )}
                    <button
                      className="link-btn"
                      onClick={() =>
                        navigator.clipboard?.writeText(ref).then(
                          () => message.success('已复制引用'),
                          () => message.error('复制失败'),
                        )
                      }
                    >
                      复制
                    </button>
                  </span>
                );
              })
            )}
          </div>
        </div>
      ) : (
        <div className="t-xs t-faint" style={{ marginTop: 8 }}>
          点击泳道上的点查看该时间桶两侧的摘要与原文引用（{hhmmss(model.buckets[0].bucketStart)} 起）。
        </div>
      )}

      {model.warnings.length > 0 && (
        <div className="tl-warn">
          {model.warnings.map((w, i) => (
            <div key={i} className="t-xs" style={{ color: 'var(--warn)' }}>
              {w}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
