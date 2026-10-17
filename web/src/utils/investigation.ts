import type { AgentEvent, QueryResult, StageEvent, ToolCallEvent } from '../types/events';
import type { Turn } from '../types/session';
import { matchEvidenceDetail, type EvidenceItem } from './evidence';

export type ActionStatus = 'pending' | 'running' | 'completed' | 'blocked' | 'failed';

/** 一条 Investigation Timeline 上的 Agent Action（严格对应真实事件，不虚构） */
export interface TimelineAction {
  key: string;
  /** 大写动作名，如 QUERY APPLICATION LOGS */
  name: string;
  status: ActionStatus;
  /** 简述（取自真实事件内容） */
  summary: string;
  /** 所属轮次（0 起）——多轮追问时用于把同名动作区分开（否则第二轮的 ANALYZE/DIAGNOSE 会被并进第一轮） */
  turn?: number;
  /** Agent 自己声明的意图（取自其「下一步策略」，缺失则不显示） */
  purpose?: string;
  /** 折叠区完整原文（保留 Agent 输出，不丢功能） */
  detail?: string;
  dataSource?: string;
  target?: string;
  resultText?: string;
  durationMs?: number;
  at: number;
  step?: number | null;
  evidenceIds: string[];
  warnings: string[];
}

export interface DiagnosisFinding {
  text: string;
  evidenceIds: string[];
  /** 该结论依据的具体数据片段（命中的最长锚点） */
  anchor?: string;
}

export interface Diagnosis {
  rootCauseHeadline: string;
  confidence: 'High' | 'Medium' | 'Low' | 'Unknown';
  confidenceReason: string;
  confirmed: DiagnosisFinding[];
  suspected: DiagnosisFinding[];
  needsVerification: string[];
  nextSteps: string[];
  evidenceBacked: boolean;
  complete: boolean;
}

/* ---------------------------------------------------------------------------
   基础提取
   ------------------------------------------------------------------------- */

function parseResult(raw: string | undefined): QueryResult | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as QueryResult;
  } catch {
    return null;
  }
}

function firstClause(text: string, max = 120): string {
  const flat = text
    .replace(/[#*`>]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  const seg = flat.split(/[。；;\n]/)[0] ?? flat;
  return seg.length > max ? seg.slice(0, max) + '…' : seg;
}

/** Agent 自己声明的下一步意图（真实取自 analysis_strategy），用于 Action 的 Purpose */
function readIntent(events: AgentEvent[], beforeTs: number): string | undefined {
  let found: string | undefined;
  for (const e of events) {
    if (e.type !== 'analysis' || e.timestamp > beforeTs) continue;
    const c = e.content ?? '';
    if (!/strateg|策略|下一步/i.test(e.subType) && !/下一步策略/.test(c)) continue;
    const txt = c.replace(/.*下一步策略[:：]?/, '').trim();
    if (txt) found = firstClause(txt, 90);
  }
  return found;
}

export interface Supervision {
  verdict: 'PASS' | 'FAIL' | 'OPTIMIZE' | null;
  score: string | null;
  suggestions: string[];
  text: string;
}

/** 从 supervision 事件提取真实的质量校验结论与改进建议 */
export function readSupervision(events: AgentEvent[]): Supervision {
  let verdict: Supervision['verdict'] = null;
  let score: string | null = null;
  const suggestions: string[] = [];
  let text = '';

  for (const e of events) {
    if (e.type !== 'supervision') continue;
    const c = e.content ?? '';
    if (!c) continue;
    text = c;
    const v = c.match(/(?:是否通过|监督结果|检查结果)[:：]?\s*(PASS|FAIL|OPTIMIZE)/);
    if (v) verdict = v[1] as Supervision['verdict'];
    const s = c.match(/质量评分[:：]?\s*([\d.]+\s*分?)/);
    if (s) score = s[1].replace(/\s+/g, '');
    const idx = c.search(/改进建议[:：]/);
    if (idx >= 0) {
      for (const line of c.slice(idx).split('\n').slice(1)) {
        const raw = line.trim();
        if (!raw) continue;
        // 只接受带符号的列表项；遇到非列表行（如「🚦 监督结果: …」）即认为区块结束
        if (!/^[-*•]\s+|^\d+[.)]\s+/.test(raw)) break;
        const t = raw.replace(/^[-*•]\s+|^\d+[.)]\s+/, '').trim();
        if (t.length >= 6) suggestions.push(t);
      }
    }
  }
  return { verdict, score, suggestions: suggestions.slice(0, 8), text };
}

/* ---------------------------------------------------------------------------
   Investigation Timeline
   ------------------------------------------------------------------------- */

const TOOL_ACTION: Record<string, string> = {
  describe_entity: 'INSPECT DATA SOURCES',
  query_logs: 'QUERY APPLICATION LOGS',
  query_orders: 'QUERY ORDER DATABASE',
  query_payments: 'QUERY PAYMENT DATABASE',
  query_correlated_timeline: 'ALIGN LOGS & ORDERS',
  query_log_summary: 'SUMMARIZE LOG PATTERNS',
  query_fault_baseline: 'COMPARE FAULT vs BASELINE',
};

function actionName(toolName: string): string {
  return TOOL_ACTION[toolName] ?? `CALL ${toolName.toUpperCase()}`;
}

function newAction(partial: Partial<TimelineAction> & { key: string; name: string; at: number }): TimelineAction {
  return {
    status: 'running',
    summary: '',
    evidenceIds: [],
    warnings: [],
    ...partial,
  } as TimelineAction;
}

/**
 * 事件属于哪一轮提问：取"最后一个开始时间 ≤ 事件时间"的轮次序号（0 起）
 */
function turnIndexOf(turns: Turn[], ts: number): number {
  let idx = 0;
  for (let i = 0; i < turns.length; i++) {
    if (turns[i].at <= ts) idx = i;
    else break;
  }
  return idx;
}

/**
 * 当前轮（最后一次提问之后）的事件。
 * <p>
 * 结论 / 诊断 / 耗时只应看当前轮；证据与时间线则要看全会话（追加式事件流，可回看）。
 * 旧数据没有 turns 时返回全部，行为与改动前一致。
 */
export function eventsOfCurrentTurn(events: AgentEvent[], turns: Turn[]): AgentEvent[] {
  const last = turns[turns.length - 1];
  if (!last) return events;
  return events.filter((e) => e.timestamp >= last.at);
}

/**
 * 把事件流还原成结构化的调查动作序列。
 * 每个 Action 严格对应真实事件（分析 / 工具调用 / 质量校验 / 总结）。
 *
 * @param turns 会话的提问轮次（追加式）。多轮时：
 *              ① 每个问题作为一条 `USER QUESTION` 动作插在时间线上（"之前问过什么"看得见）；
 *              ② 同名动作按轮次区分，避免第二轮的「理解问题 / 结论」被并进第一轮（那是信息丢失）。
 */
export function buildTimeline(events: AgentEvent[], running: boolean, turns: Turn[] = []): TimelineAction[] {
  const out: TimelineAction[] = [];
  const pendingByTool = new Map<string, TimelineAction[]>();
  let querySeq = 0;
  let lastPurpose: string | undefined;
  const turnOf = (ts: number) => turnIndexOf(turns, ts);

  for (const e of events) {
    if (e.type === 'tool_call') {
      const tc = e as ToolCallEvent;

      if (tc.subType === 'start') {
        // Purpose 去重：同一段「下一步策略」在多轮里反复出现时只展示一次
        const intent = readIntent(events, tc.timestamp);
        const purpose = intent && intent !== lastPurpose ? intent : undefined;
        if (purpose) lastPurpose = purpose;
        const action = newAction({
          key: `${tc.toolName}-${tc.timestamp}-${out.length}`,
          name: actionName(tc.toolName),
          summary: `正在执行 ${tc.toolName}`,
          purpose,
          dataSource: tc.dsId ?? undefined,
          at: tc.timestamp,
          step: tc.step,
          turn: turnOf(tc.timestamp),
        });
        out.push(action);
        const list = pendingByTool.get(tc.toolName) ?? [];
        list.push(action);
        pendingByTool.set(tc.toolName, list);
        continue;
      }

      const list = pendingByTool.get(tc.toolName);
      const action = list && list.length > 0 ? list.shift() : undefined;
      const q = parseResult(tc.result);
      const isQuery = tc.toolName.startsWith('query_');
      if (isQuery && tc.result) querySeq += 1;

      const target =
        action ??
        newAction({
          key: `${tc.toolName}-r-${tc.timestamp}-${out.length}`,
          name: actionName(tc.toolName),
          summary: '',
          purpose: undefined,
          dataSource: tc.dsId ?? undefined,
          at: tc.timestamp,
          step: tc.step,
          status: 'completed',
          turn: turnOf(tc.timestamp),
        });
      if (!action) out.push(target);

      const rows = q?.rows?.length ?? 0;
      const warned = (q?.warnings?.length ?? 0) > 0;
      target.status = warned && rows === 0 ? 'failed' : 'completed';
      target.warnings = q?.warnings ?? [];
      target.durationMs = q?.costMs;

      if (isQuery) {
        target.resultText = `${q?.total ?? 0} ${q?.entityCode === 'orders' ? 'records' : 'matches'}`;
        target.evidenceIds = [`EV-${String(querySeq).padStart(2, '0')}`];
        target.summary = `检索条件 ${tc.arguments || '{}'}`;
      } else {
        let n = 0;
        try {
          const arr = JSON.parse(tc.result ?? '[]') as unknown[];
          n = Array.isArray(arr) ? arr.length : 0;
        } catch {
          n = 0;
        }
        target.resultText = `${n} sources`;
        target.summary = `发现 ${n} 个可用数据源`;
      }
      continue;
    }

    if (e.type === 'analysis') {
      // 按「轮次 + step」去重：step 每轮都从 1 重新计数，只用 step 会把第二轮并进第一轮
      const turn = turnOf(e.timestamp);
      const a = out.find((x) => x.name === 'UNDERSTAND INCIDENT' && x.step === e.step && x.turn === turn);
      if (a) {
        a.detail = `${a.detail ?? ''}\n\n${e.content}`.trim();
        a.summary = firstClause(e.content ?? '');
      } else {
        out.push(
          newAction({
            key: `analysis-${e.timestamp}`,
            name: 'UNDERSTAND INCIDENT',
            status: 'completed',
            summary: firstClause(e.content ?? ''),
            detail: e.content,
            at: e.timestamp,
            step: e.step,
            turn,
          }),
        );
      }
      continue;
    }

    if (e.type === 'supervision') {
      const text = e.content ?? '';
      const v = text.match(/(?:是否通过|监督结果|检查结果)[:：]?\s*(PASS|FAIL|OPTIMIZE)/)?.[1];
      out.push(
        newAction({
          key: `sup-${e.timestamp}`,
          name: 'VALIDATE FINDINGS',
          status: v === 'FAIL' ? 'blocked' : 'completed',
          summary: v ? `质量校验：${v}` : firstClause(text),
          detail: text,
          at: e.timestamp,
          step: e.step,
          turn: turnOf(e.timestamp),
          warnings: v === 'FAIL' ? ['质量校验未通过，结论需复核'] : [],
        }),
      );
      continue;
    }

    if (e.type === 'summary') {
      const turn = turnOf(e.timestamp);
      // 同上：每轮都有自己的 DIAGNOSE，不能被第一轮吃掉
      const existing = out.find((x) => x.name === 'DIAGNOSE' && x.turn === turn);
      if (existing) {
        if ((e.content?.length ?? 0) > (existing.detail?.length ?? 0)) existing.detail = e.content;
        continue;
      }
      out.push(
        newAction({
          key: `summary-${e.timestamp}`,
          name: 'DIAGNOSE',
          status: 'completed',
          summary: firstClause(e.content ?? ''),
          detail: e.content,
          at: e.timestamp,
          step: e.step,
          turn,
        }),
      );
    }
  }

  // 多轮时把每轮问题也画在时间线上（单轮不加，避免与标题重复）：它是各轮之间的天然分隔
  if (turns.length > 1) {
    turns.forEach((t, i) => {
      if (!t.question) return;
      out.push(
        newAction({
          key: `turn-${i}-${t.at}`,
          name: i === 0 ? 'USER QUESTION' : `USER QUESTION #${i + 1}`,
          status: 'completed',
          summary: t.question,
          at: t.at,
          turn: i,
        }),
      );
    });
  }

  if (running && !out.some((a) => a.status === 'running')) {
    out.push(
      newAction({
        key: 'pending',
        name: 'AWAITING NEXT ACTION',
        status: 'pending',
        summary: 'Agent 正在继续调查…',
        at: Date.now(),
      }),
    );
  }

  return out.sort((a, b) => a.at - b.at);
}

/* ---------------------------------------------------------------------------
   Diagnosis（独立区域）
   ------------------------------------------------------------------------- */

/** 把 Markdown 拆成可比对的陈述片段（段落 / 列表项 / 表格行） */
function segmentsOf(md: string): Array<{ text: string; heading?: string }> {
  const out: Array<{ text: string; heading?: string }> = [];
  let heading: string | undefined;
  let buf: string[] = [];

  const flush = () => {
    if (buf.length === 0) return;
    const text = buf.join(' ').replace(/[*`]/g, '').trim();
    if (text.length >= 8) out.push({ text, heading });
    buf = [];
  };

  for (const raw of md.split('\n')) {
    const line = raw.trim();
    if (!line) {
      flush();
      continue;
    }
    if (/^#{1,6}\s/.test(line)) {
      flush();
      heading = line.replace(/^#{1,6}\s*/, '').trim();
      continue;
    }
    if (/^\|/.test(line)) {
      flush();
      if (/^\|[\s:|-]+\|$/.test(line)) continue;
      const cells = line
        .split('|')
        .map((c) => c.replace(/[*`]/g, '').trim())
        .filter(Boolean);
      if (cells.length > 0) out.push({ text: cells.join(' ｜ '), heading });
      continue;
    }
    if (/^[-*+]\s|^\d+[.)]\s/.test(line)) {
      flush();
      out.push({
        text: line.replace(/^[-*+]\s|^\d+[.)]\s/, '').replace(/[*`]/g, '').trim(),
        heading,
      });
      continue;
    }
    buf.push(line);
  }
  flush();
  return out.filter((s) => s.text.length >= 8);
}

const NEXT_STEP_RE = /建议|下一步|需要(?:检查|确认|验证)|行动|优先|P[0-3]\s*｜|责任方|应用运维|运维侧|DBA/;
/** 表头/泛化行不作为「结论」参与 Confirmed / Suspected 判定 */
const HEADER_RE = /^(时间|判定维度|优先级|维度|项目|结论|行动|责任方|序号|#)(\s*｜|$)/;
/** 表格分隔线、查询语句脚手架、字段枚举行等非结论片段 */
const SCAFFOLD_RE = /[─—]{3,}|query_(orders|logs)\s*\(|SELECT\s+|startTime\s*=|endTime\s*=|orderNo\s*=|(\w+\s*\/\s*){3,}/i;

/** 单元格都很短的表格行 = 表头 */
function isHeaderRow(text: string): boolean {
  const cells = text.split('｜').map((c) => c.trim());
  return cells.length >= 3 && cells.every((c) => c.length <= 4);
}

/**
 * 由真实数据派生诊断（只做机械归纳，不替 Agent 下判断）：
 *   - 能锚定到 rows>0 证据的陈述 → CONFIRMED（并给出依据的数据片段）
 *   - 无数据锚点的陈述           → SUSPECTED
 *   - 0 命中证据 / 校验未通过 / 未正常收尾 → NEEDS VERIFICATION
 */
export function buildDiagnosis(
  events: AgentEvent[],
  evidence: EvidenceItem[],
  summary: string,
  running: boolean,
): Diagnosis | null {
  if (!summary) return null;

  const sup = readSupervision(events);
  const complete = events.some((e) => e.type === 'complete');
  const backedEvidence = evidence.filter((e) => e.rowsCount > 0);
  const emptyEvidence = evidence.filter((e) => e.rowsCount === 0);
  const evidenceBacked = backedEvidence.length > 0;

  const segs = segmentsOf(summary);
  const confirmed: DiagnosisFinding[] = [];
  const suspected: DiagnosisFinding[] = [];
  const nextSteps: string[] = [];

  for (const s of segs) {
    if (s.text.length < 12) continue;
    if (HEADER_RE.test(s.text) || isHeaderRow(s.text) || SCAFFOLD_RE.test(s.text)) continue;

    const hits = matchEvidenceDetail(s.text, evidence);
    const supported = hits.filter((h) => backedEvidence.some((e) => e.id === h.id));
    // 行动项优先归入下一步，而不是当作"已确认结论"
    if (NEXT_STEP_RE.test(s.text)) {
      if (nextSteps.length < 6) nextSteps.push(s.text);
      continue;
    }
    if (supported.length > 0) {
      // 去重：已被更完整片段覆盖的短片段不再单列
      if (confirmed.some((f) => f.text.includes(s.text) || s.text.includes(f.text))) continue;
      if (confirmed.length < 6) {
        confirmed.push({
          text: s.text,
          evidenceIds: supported.map((h) => h.id),
          anchor: supported.sort((a, b) => b.anchor.length - a.anchor.length)[0].anchor,
        });
      }
    } else if (suspected.length < 5) {
      suspected.push({ text: s.text, evidenceIds: hits.map((h) => h.id) });
    }
  }

  for (const sug of sup.suggestions) {
    if (nextSteps.length >= 6) break;
    if (!nextSteps.includes(sug)) nextSteps.push(sug);
  }

  const needsVerification: string[] = [];
  for (const e of emptyEvidence) {
    needsVerification.push(`${e.id} ${e.title}（${e.target}）未命中数据 —— 该方向尚无数据支撑`);
  }
  if (sup.verdict === 'FAIL' || sup.verdict === 'OPTIMIZE') {
    needsVerification.push(`质量校验结论为 ${sup.verdict} —— 结论需要复核后再采纳`);
  }
  if (!running && !complete) {
    needsVerification.push('本轮调查未收到完成标识（达到最大轮次或中断）');
  }
  if (evidence.length === 0) {
    needsVerification.push('本次调查尚未产生任何数据证据');
  }

  let confidence: Diagnosis['confidence'];
  let reason: string;
  if (evidence.length === 0) {
    confidence = 'Unknown';
    reason = '没有任何数据证据，无法评估结论可信度';
  } else if (sup.verdict === 'FAIL' || !evidenceBacked) {
    confidence = 'Low';
    reason = !evidenceBacked
      ? `现有 ${evidence.length} 条证据均未命中数据`
      : '质量校验未通过但存在命中数据';
  } else if (sup.verdict === 'PASS' && evidenceBacked) {
    confidence = 'High';
    reason = `${backedEvidence.length} 条证据命中真实数据，且质量校验通过`;
  } else {
    confidence = 'Medium';
    reason = `${backedEvidence.length} 条证据命中真实数据${
      sup.verdict ? `，质量校验为 ${sup.verdict}` : '，未收到质量校验结论'
    }`;
  }

  const rootSeg = segs.find((s) => s.heading && /根因|原因|root cause/i.test(s.heading)) ?? segs[0];

  return {
    rootCauseHeadline: firstClause(rootSeg?.text ?? '', 160),
    confidence,
    confidenceReason: reason,
    confirmed,
    suspected,
    needsVerification,
    nextSteps,
    evidenceBacked,
    complete,
  };
}

/** 结论正文（取**最后一次**完成的 summary；追加式事件流下"最长的那次"可能是上一轮的） */
export function buildConclusion(events: AgentEvent[]): string {
  const summaries = events.filter((e): e is StageEvent => e.type === 'summary' && !!e.content);
  if (summaries.length === 0) return '';
  const completed = summaries.filter((s) => s.completed);
  const pool = completed.length > 0 ? completed : summaries;
  return pool[pool.length - 1].content;
}

export function runDuration(events: AgentEvent[]): number | null {
  if (events.length < 2) return null;
  const first = events[0].timestamp;
  const last = events[events.length - 1].timestamp;
  return last >= first ? last - first : null;
}
