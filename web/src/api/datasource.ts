/**
 * 数据源健康 API（M4.1 端点 `GET /api/v1/datasource/health`）
 *
 * 这是「数据源是否真的可用」的**权威来源**：由后端对每个已注册 Provider 跑真实探活得到
 * （MySQL 会 `SELECT 1`、ES 会 `GET _cluster/health`），而不是前端从查询结果反推。
 *
 * 默认走后端 30s 缓存；需要实时状态时传 `refresh=true` 强制重查。
 */
const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://127.0.0.1:8099';

/** 共享口令（可选）：后端配了 `xfg.web.api-key` 时健康端点也要带 `X-API-Key` */
const API_KEY = (import.meta.env.VITE_API_KEY as string | undefined) ?? '';
const authHeaders = (): Record<string, string> => (API_KEY ? { 'X-API-Key': API_KEY } : {});

/** 数据源对外提供的实体与能力（与 `describe_entity` 的实体摘要同形） */
export interface HealthEntity {
  entityCode: string;
  entityName?: string | null;
  capability?: string | null;
}

/** 单个数据源的健康快照（对应后端 `SourceHealth`） */
export interface SourceHealth {
  dsId: string;
  dsType: string;
  healthy: boolean;
  /** 不健康时的原始原因（健康时为 null） */
  message?: string | null;
  /** 本次健康检查耗时（毫秒）；失败时同样有值，可据此看出"超时"还是"被拒绝" */
  costMs?: number;
  /** 可操作提示：由后端从 conn_config 推导（如"要注入哪个环境变量"）；无据可依时为 null */
  hint?: string | null;
  entities?: HealthEntity[] | null;
}

/** 端点响应体 */
export interface HealthReport {
  /** 检查时刻（毫秒时间戳）；命中缓存时为缓存写入时刻 */
  checkedAt: number;
  /** 结果是否来自后端缓存 */
  cached: boolean;
  summary?: { total?: number; healthy?: number; unhealthy?: number };
  sources?: SourceHealth[] | null;
}

/**
 * 拉取全部数据源的健康状态
 *
 * @param refresh 为 true 时忽略后端缓存、强制重新探活
 */
export async function fetchDatasourceHealth(refresh = false, signal?: AbortSignal): Promise<HealthReport> {
  const url = `${API_BASE}/api/v1/datasource/health${refresh ? '?refresh=true' : ''}`;
  const resp = await fetch(url, { signal, headers: authHeaders() });
  if (!resp.ok) {
    throw new Error(`健康端点请求失败 HTTP ${resp.status}`);
  }

  const body = (await resp.json()) as HealthReport | null;
  if (!body || typeof body !== 'object' || !Array.isArray(body.sources)) {
    throw new Error('健康端点返回结构不符合预期');
  }

  // 防御：丢掉缺 dsId 的脏数据，避免面板出现空行
  return { ...body, sources: body.sources.filter((s) => !!s && typeof s.dsId === 'string') };
}
