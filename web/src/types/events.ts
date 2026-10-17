/**
 * 后端 SSE 事件的 TypeScript 契约（M3.3）。
 *
 * 与后端两个实体一一对应，勿手改字段名：
 *  - ToolCallEventEntity        → ToolCallEvent
 *  - AutoAgentExecuteResultEntity → StageEvent / CompleteEvent
 */

export type AgentEvent = ToolCallEvent | StageEvent | CompleteEvent;

export interface ToolCallEvent {
  type: 'tool_call';
  subType: 'start' | 'result';
  toolName: string;
  dsId: string | null;
  entityCode: string | null;
  step: number;
  sessionId: string;
  /** 模型传入的 toolInput 原样（JSON 字符串） */
  arguments: string;
  /** 仅 result 事件：QueryResult 的 JSON 字符串（含 rawRef + 行级 _rawRef） */
  result?: string;
  timestamp: number;
}

export interface StageEvent {
  type: 'analysis' | 'execution' | 'supervision' | 'summary';
  subType: string;
  step: number | null;
  content: string;
  completed: boolean;
  sessionId: string;
  timestamp: number;
}

export interface CompleteEvent {
  type: 'complete';
  content: string;
  completed: boolean;
  sessionId: string;
  timestamp: number;
}

/** tool_call result 里的 QueryResult 镜像 */
export interface QueryResult {
  dsId: string;
  entityCode: string;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  total: number;
  truncated: boolean;
  costMs: number;
  rawRef: string | null;
  warnings: string[];
}

/** 故障窗字段（M8）：全部可选，不传 = 与旧版行为一致 */
export interface FaultWindowFields {
  /** 故障开始（ISO-8601） */
  faultStart?: string;
  /** 故障结束（ISO-8601，可空 = 进行中） */
  faultEnd?: string;
  /** 上次故障结束（ISO-8601，可选）：用于构造"避开余波"的前基线 */
  prevFaultEnd?: string;
  /** 下次故障开始（ISO-8601，可选）：给出才构造后基线 */
  nextFaultStart?: string;
  /** 故障标签（可选，仅展示与提示词上下文） */
  faultLabel?: string;
}

/** POST /api/v1/agent/auto_agent 请求体 */
export interface AutoAgentRequest extends FaultWindowFields {
  aiAgentId: string;
  message: string;
  sessionId: string;
  maxStep: number;
}
