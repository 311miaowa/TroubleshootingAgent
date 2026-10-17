import { useCallback, useEffect, useRef, useState } from 'react';
import { streamAgentSSE } from '../api/agentSSE';
import type { AgentEvent, AutoAgentRequest, FaultWindowFields } from '../types/events';
import { MAX_SESSIONS, PERSIST_KEY, type RunStatus, type Session } from '../types/session';

function newId(): string {
  return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

function makeTitle(question: string): string {
  const t = question.trim().replace(/\s+/g, ' ');
  return t.length > 42 ? t.slice(0, 42) + '…' : t || '未命名调查';
}

function load(): Session[] {
  try {
    const raw = localStorage.getItem(PERSIST_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw) as Session[];
    if (!Array.isArray(list)) return [];
    // 刷新后未完成的运行标记为 Blocked（中断），而不是假装完成或退回未开始
    return list.map((s) => {
      const interrupted = s.status === 'streaming' || s.status === 'connecting';
      return {
        ...s,
        // 兼容旧数据（当时只有单个 question 字段、没有 turns）：迁移成一轮，避免历史丢失
        turns:
          Array.isArray(s.turns) && s.turns.length > 0
            ? s.turns
            : s.question
              ? [{ question: s.question, at: s.createdAt ?? Date.now() }]
              : [],
        status: interrupted ? 'blocked' : s.status,
        error: interrupted ? '运行已中断（页面刷新）' : s.error,
      };
    });
  } catch {
    return [];
  }
}

function save(sessions: Session[]): void {
  try {
    localStorage.setItem(PERSIST_KEY, JSON.stringify(sessions.slice(0, MAX_SESSIONS)));
  } catch {
    /* 配额溢出时静默放弃持久化 */
  }
}

/**
 * 多会话存储：历史调查 + 每个会话独立的事件流与运行状态。
 * 事件流在内存中累积，并持久化到 localStorage（上限 MAX_SESSIONS）。
 */
export function useSessions() {
  const [sessions, setSessions] = useState<Session[]>(load);
  const [activeId, setActiveId] = useState<string>(() => sessions[0]?.id ?? '');
  const controllers = useRef(new Map<string, AbortController>());

  useEffect(() => {
    save(sessions);
  }, [sessions]);

  const patch = useCallback((id: string, fn: (s: Session) => Session) => {
    setSessions((prev) => prev.map((s) => (s.id === id ? fn(s) : s)));
  }, []);

  const createSession = useCallback((aiAgentId = '4'): string => {
    const id = newId();
    const now = Date.now();
    setSessions((prev) => [
      {
        id,
        question: '',
        title: '新的调查',
        createdAt: now,
        updatedAt: now,
        status: 'idle',
        error: null,
        events: [],
        turns: [],
        aiAgentId,
      },
      ...prev,
    ]);
    setActiveId(id);
    return id;
  }, []);

  const deleteSession = useCallback((id: string) => {
    controllers.current.get(id)?.abort();
    controllers.current.delete(id);
    setSessions((prev) => {
      const next = prev.filter((s) => s.id !== id);
      setActiveId((cur) => (cur === id ? next[0]?.id ?? '' : cur));
      return next;
    });
  }, []);

  const renameSession = useCallback(
    (id: string, title: string) => patch(id, (s) => ({ ...s, title: title.trim() || s.title })),
    [patch],
  );

  const stop = useCallback(
    (id: string) => {
      controllers.current.get(id)?.abort();
      controllers.current.delete(id);
      patch(id, (s) => ({ ...s, status: s.status === 'done' ? s.status : 'blocked' }));
    },
    [patch],
  );

  const run = useCallback(
    (sessionId: string, question: string, aiAgentId: string, maxStep: number, fault?: FaultWindowFields) => {
      controllers.current.get(sessionId)?.abort();
      const controller = new AbortController();
      controllers.current.set(sessionId, controller);

      const now = Date.now();
      // 追加式：新问题**不清空**已有事件流，也不覆盖已有问题——
      // 同一会话可以多轮追问，之前的问题与记录必须留得住（见 M3 UI 设计文档"追加式流/可回看"）。
      patch(sessionId, (s) => {
        const isFirstTurn = !s.question;
        return {
          ...s,
          question: isFirstTurn ? question : s.question,
          title: isFirstTurn ? makeTitle(question) : s.title,
          turns: [...(s.turns ?? []), { question, at: now }],
          status: 'connecting',
          error: null,
          events: s.events ?? [],
          aiAgentId,
          updatedAt: now,
        };
      });

      const body: AutoAgentRequest = {
        aiAgentId,
        message: question,
        sessionId,
        maxStep,
        // M8：故障窗（可选）。只在有值时才带上，避免给后端塞空字符串
        ...(fault?.faultStart ? { faultStart: fault.faultStart } : {}),
        ...(fault?.faultEnd ? { faultEnd: fault.faultEnd } : {}),
        ...(fault?.prevFaultEnd ? { prevFaultEnd: fault.prevFaultEnd } : {}),
        ...(fault?.nextFaultStart ? { nextFaultStart: fault.nextFaultStart } : {}),
        ...(fault?.faultLabel ? { faultLabel: fault.faultLabel } : {}),
      };

      streamAgentSSE(
        body,
        (event: AgentEvent) => {
          patch(sessionId, (s) => ({
            ...s,
            events: [...s.events, event],
            status: event.type === 'complete' ? 'done' : 'streaming',
            updatedAt: Date.now(),
          }));
        },
        controller.signal,
      )
        .then(() => {
          // 流结束但没收到 complete（未正常收尾 / 中途断流）→ Blocked，而非谎报完成
          patch(sessionId, (s) =>
            s.status === 'streaming'
              ? { ...s, status: 'blocked', error: s.error ?? '调查中断流，未收到完成标识' }
              : s,
          );
        })
        .catch((e: unknown) => {
          if (e instanceof DOMException && e.name === 'AbortError') return;
          const msg = e instanceof Error ? e.message : String(e);
          patch(sessionId, (s) => ({ ...s, status: 'error', error: msg, updatedAt: Date.now() }));
        })
        .finally(() => {
          controllers.current.delete(sessionId);
        });
    },
    [patch],
  );

  const active = sessions.find((s) => s.id === activeId) ?? null;

  const setStatus = useCallback(
    (id: string, status: RunStatus) => patch(id, (s) => ({ ...s, status })),
    [patch],
  );

  return {
    sessions,
    active,
    activeId,
    setActiveId,
    createSession,
    deleteSession,
    renameSession,
    run,
    stop,
    setStatus,
  };
}
