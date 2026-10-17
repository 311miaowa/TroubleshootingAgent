/**
 * 真实数据流水线验证（无浏览器）：
 * 读取后端 SSE 抓包文件 → 解析事件 → 跑与 UI 完全相同的派生逻辑 → 打印结果。
 * 用于证明重构后的 Timeline / Evidence / Diagnosis 在**真实 Agent 输出**上成立。
 *
 * 运行：npx esbuild scripts/verify-real.ts --bundle --platform=node --format=esm --outfile=scripts/.verify.mjs && node scripts/.verify.mjs <sse-file>
 */
import fs from 'node:fs';
import { parseSseChunk } from '../src/utils/parseSseFrame';
import { buildEvidence, buildSources } from '../src/utils/evidence';
import { buildConclusion, buildDiagnosis, buildTimeline } from '../src/utils/investigation';
import type { AgentEvent } from '../src/types/events';

const file = process.argv[2];
if (!file) {
  console.error('usage: node .verify.mjs <sse-file>');
  process.exit(2);
}

const raw = fs.readFileSync(file, 'utf8');
const { events: frames } = parseSseChunk(raw);
const events: AgentEvent[] = [];
for (const f of frames) {
  try {
    events.push(JSON.parse(f) as AgentEvent);
  } catch {
    /* skip bad frame */
  }
}

const sources = buildSources(events);
const evidence = buildEvidence(events, sources);
const running = !events.some((e) => e.type === 'complete');
const conclusion = buildConclusion(events);
const actions = buildTimeline(events, running);
const diag = buildDiagnosis(events, evidence, conclusion, running);

console.log(`事件数 ${events.length}`);
console.log(`\n=== Data Sources (${sources.length}) ===`);
for (const s of sources) console.log(`  ${s.dsId} · ${s.entityName} · ${s.capability} · ${s.physicalName ?? '-'}`);

console.log(`\n=== Evidence (${evidence.length}) ===`);
for (const e of evidence) {
  console.log(`  ${e.id} [${e.typeLabel}] ${e.title} · ${e.target} · ${e.query.total} hits / ${e.rowsCount} rows · ${e.durationMs}ms`);
  console.log(`     QUERY: ${e.conditions.join(' AND ')}`);
  console.log(`     INTERP: ${e.interpretation}`);
}

console.log(`\n=== Investigation Timeline (${actions.length}) ===`);
for (const a of actions) {
  const bits = [
    a.status.toUpperCase().padEnd(9),
    a.name.padEnd(24),
    a.resultText ?? '',
    a.durationMs != null ? `${a.durationMs}ms` : '',
    a.evidenceIds.length ? `→ ${a.evidenceIds.join(',')}` : '',
    a.warnings.length ? `⚠ ${a.warnings[0]}` : '',
  ].filter(Boolean);
  console.log(`  ${bits.join('  ')}`);
  if (a.purpose) console.log(`     PURPOSE: ${a.purpose}`);
}

console.log('\n=== Diagnosis ===');
if (!diag) {
  console.log('  (尚未产生总结)');
} else {
  console.log(`  root cause : ${diag.rootCauseHeadline}`);
  console.log(`  confidence : ${diag.confidence} — ${diag.confidenceReason}`);
  console.log(`  evidenceBacked=${diag.evidenceBacked} complete=${diag.complete}`);
  console.log(`  confirmed  (${diag.confirmed.length}):`);
  for (const f of diag.confirmed) console.log(`     ● [${f.evidenceIds.join(',')}] ${f.text.slice(0, 90)}`);
  console.log(`  suspected  (${diag.suspected.length}):`);
  for (const f of diag.suspected) console.log(`     ○ ${f.text.slice(0, 90)}`);
  console.log(`  needs verification (${diag.needsVerification.length}):`);
  for (const t of diag.needsVerification) console.log(`     · ${t}`);
  console.log(`  next steps (${diag.nextSteps.length}):`);
  for (const t of diag.nextSteps) console.log(`     ${t.slice(0, 100)}`);
}

// 硬性断言：真实数据上引用锚定必须成立（此前版本在这里失败）
const cited = new Set<string>();
for (const a of actions) a.evidenceIds.forEach((i) => cited.add(i));
for (const f of diag?.confirmed ?? []) f.evidenceIds.forEach((i) => cited.add(i));
const failures: string[] = [];
if (evidence.length > 0 && cited.size === 0) failures.push('有证据但动作/结论中都未建立任何引用关联');
if (diag && evidence.some((e) => e.rowsCount > 0) && diag.confirmed.length === 0) {
  failures.push('存在命中数据的证据，但 Diagnosis 未归纳出任何 Confirmed 结论');
}
console.log(failures.length === 0 ? '\nREAL-DATA CHECK: PASS' : `\nREAL-DATA CHECK: FAIL\n - ${failures.join('\n - ')}`);
process.exit(failures.length === 0 ? 0 : 1);
