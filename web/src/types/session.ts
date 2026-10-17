import type { AgentEvent } from './events';

export type RunStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'blocked' | 'error';

/**
 * 会话内的一轮提问。
 * <p>
 * 同一会话可以有多轮（追问）：每轮问题都记在这里，**不再覆盖**上一条——
 * 之前只存一个 `question` 字段，第二次提问就把上一轮问题冲掉了（UI 上表现为"之前的问题看不到记录"）。
 */
export interface Turn {
  /** 该轮的用户问题原文 */
  question: string;
  /** 该轮开始时间（epoch ms）；用于把事件流切成"当前轮"、以及在时间线上定位问题节点 */
  at: number;
}

/** 一次故障调查会话 */
export interface Session {
  id: string;
  /** 会话的原始问题（首轮问题，用于标题与"这次在查什么"） */
  question: string;
  /** 侧栏展示标题（可重命名） */
  title: string;
  createdAt: number;
  updatedAt: number;
  status: RunStatus;
  error: string | null;
  /** 该会话的完整 SSE 事件流（**追加式**：多轮事件全部保留，不因新一轮提问而清空） */
  events: AgentEvent[];
  /** 该会话的提问轮次（追加式，问题与时间） */
  turns: Turn[];
  aiAgentId: string;
}

export const PERSIST_KEY = 'm3.workbench.sessions.v1';
export const MAX_SESSIONS = 40;
