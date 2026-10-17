import type { AgentEvent, AutoAgentRequest } from '../types/events';
import { parseSseChunk } from '../utils/parseSseFrame';

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://127.0.0.1:8099';

/**
 * 共享口令（可选）：后端配置了 `xfg.web.api-key` 时，所有 /api 请求必须带 `X-API-Key`。
 * 前端通过 `VITE_API_KEY` 构建时注入；未配置则不带（对应后端"无鉴权"的本地模式）。
 */
const API_KEY = (import.meta.env.VITE_API_KEY as string | undefined) ?? '';

/** 需要鉴权时返回 X-API-Key 头，否则返回空对象 */
export const authHeaders = (): Record<string, string> => (API_KEY ? { 'X-API-Key': API_KEY } : {});

/**
 * 以 POST + ReadableStream 消费后端 SSE（端点必须 POST，不能用 EventSource）。
 * 逐帧解析后回调 onEvent；complete 事件出现后自然结束。
 */
export async function streamAgentSSE(
  body: AutoAgentRequest,
  onEvent: (event: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const resp = await fetch(`${API_BASE}/api/v1/agent/auto_agent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(body),
    signal,
  });
  if (!resp.ok) {
    throw new Error(`请求失败 HTTP ${resp.status}: ${await resp.text().catch(() => '')}`);
  }
  if (!resp.body) {
    throw new Error('响应无 body（非流式响应）');
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const { events, rest } = parseSseChunk(buffer);
      buffer = rest;

      for (const raw of events) {
        try {
          onEvent(JSON.parse(raw) as AgentEvent);
        } catch (e) {
          // 单个坏帧不影响整条流
          console.warn('解析 SSE 帧失败，已跳过:', raw, e);
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
