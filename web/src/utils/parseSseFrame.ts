/**
 * SSE 帧解析（M3.3）。
 *
 * 后端输出格式：`data: {json}\n\n`。此处按空行切事件块，提取 `data:` 行拼接后交给 JSON.parse。
 * 注意：不能用原生 EventSource（端点必须 POST），故走 fetch 流式读，由本模块切帧。
 */

export interface SseParseResult {
  /** 本次解析出的完整事件数据（每个是 data 行的原始 JSON 文本） */
  events: string[];
  /** 未完成、留待下次拼接的 buffer */
  rest: string;
}

export function parseSseChunk(buffer: string): SseParseResult {
  const events: string[] = [];
  let rest = buffer;

  // SSE 事件以空行分隔；容忍 \r\n 与 \n 混用
  let sep = rest.indexOf('\n\n');
  let sepLen = 2;
  const crlf = rest.indexOf('\r\n\r\n');
  if (crlf >= 0 && (sep < 0 || crlf < sep)) {
    sep = crlf;
    sepLen = 4;
  }

  while (sep >= 0) {
    const block = rest.slice(0, sep);
    rest = rest.slice(sep + sepLen);

    const dataLines = block
      .split('\n')
      .map((l) => l.replace(/\r$/, ''))
      .filter((l) => l.startsWith('data:'))
      .map((l) => l.slice('data:'.length).trimStart());

    if (dataLines.length > 0) {
      events.push(dataLines.join('\n'));
    }

    sep = rest.indexOf('\n\n');
    sepLen = 2;
    const nextCrlf = rest.indexOf('\r\n\r\n');
    if (nextCrlf >= 0 && (sep < 0 || nextCrlf < sep)) {
      sep = nextCrlf;
      sepLen = 4;
    }
  }

  return { events, rest };
}

export function parseEventJson(raw: string): unknown {
  if (!raw) return null;
  return JSON.parse(raw);
}
