import { Tooltip } from 'antd';
import type { Diagnosis } from '../utils/investigation';

const CONFIDENCE_CLASS: Record<Diagnosis['confidence'], string> = {
  High: 'conf high',
  Medium: 'conf medium',
  Low: 'conf low',
  Unknown: 'conf unknown',
};

/**
 * DIAGNOSIS：独立于 Timeline 的结论区。
 * 关键纪律：证据不足时不呈现为"已确定根因"，而是明确分级
 * Confirmed / Suspected / Needs verification。
 */
export default function DiagnosisPanel({
  diagnosis,
  activeEvidence,
  onCite,
}: {
  diagnosis: Diagnosis | null;
  activeEvidence: string | null;
  onCite: (id: string) => void;
}) {
  if (!diagnosis) return null;

  return (
    <section className="diag" id="investigation-diagnosis">
      <div className="section-rule">
        <span className="t-label">Diagnosis</span>
      </div>

      <div className="diag-hero">
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="t-xs t-faint">Potential root cause</div>
          <div className="diag-cause">{diagnosis.rootCauseHeadline}</div>
        </div>
        <Tooltip title={diagnosis.confidenceReason} mouseEnterDelay={0.2}>
          <div className={CONFIDENCE_CLASS[diagnosis.confidence]}>
            <span className="t-xs t-faint">Confidence</span>
            <b>{diagnosis.confidence}</b>
          </div>
        </Tooltip>
      </div>

      {!diagnosis.evidenceBacked && (
        <div className="diag-alert">
          证据不足以确认根因：当前没有任何一条证据命中真实数据。以下内容仅代表待验证的推断。
        </div>
      )}

      {diagnosis.confirmed.length > 0 && (
        <div className="diag-group">
          <div className="diag-group-head">
            <span className="diag-badge confirmed">Confirmed</span>
            <span className="t-xs t-faint">有真实数据支撑</span>
          </div>
          {diagnosis.confirmed.map((f, i) => (
            <div key={i} className="diag-finding">
              <span className="diag-mark ok">●</span>
              <span>
                {f.text}
                {f.evidenceIds.map((id) => (
                  <span
                    key={id}
                    className={`cite${activeEvidence === id ? ' is-active' : ''}`}
                    style={{ marginLeft: 6 }}
                    onClick={() => onCite(id)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => e.key === 'Enter' && onCite(id)}
                  >
                    <span className="cite-id" style={{ borderLeft: 'none', paddingLeft: 0 }}>
                      {id}
                    </span>
                  </span>
                ))}
                {f.anchor && (
                  <Tooltip title={`依据的真实数据：${f.anchor}`} mouseEnterDelay={0.2}>
                    <div className="diag-anchor">↳ {f.anchor}</div>
                  </Tooltip>
                )}
              </span>
            </div>
          ))}
        </div>
      )}

      {diagnosis.suspected.length > 0 && (
        <div className="diag-group">
          <div className="diag-group-head">
            <span className="diag-badge suspected">Suspected</span>
            <span className="t-xs t-faint">无数据锚点的推断</span>
          </div>
          {diagnosis.suspected.map((f, i) => (
            <div key={i} className="diag-finding">
              <span className="diag-mark warn">○</span>
              <span>{f.text}</span>
            </div>
          ))}
        </div>
      )}

      {diagnosis.needsVerification.length > 0 && (
        <div className="diag-group">
          <div className="diag-group-head">
            <span className="diag-badge verify">Needs verification</span>
          </div>
          {diagnosis.needsVerification.map((t, i) => (
            <div key={i} className="diag-finding">
              <span className="diag-mark muted">·</span>
              <span>{t}</span>
            </div>
          ))}
        </div>
      )}

      {diagnosis.nextSteps.length > 0 && (
        <div className="diag-group">
          <div className="diag-group-head">
            <span className="t-label">Recommended next steps</span>
          </div>
          <ol className="diag-steps">
            {diagnosis.nextSteps.map((t, i) => (
              <li key={i}>{t}</li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
