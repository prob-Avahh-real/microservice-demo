/**
 * 网关 API 客户端 —— 唯一一处「怎么调后端」的定义。
 *
 * 刻意做成纯函数 + 依赖注入（fetch 可替换），于是：
 *   1. 同一个模块既跑在 Capacitor 的 WebView 里，也能在 Node 里被测试
 *   2. 不需要 Android 设备就能验证「客户端逻辑 vs 真实网关」是否正确
 *
 * 后端统一返回 ApiResponse{ok, code, message, data}，这里原样返回外壳，
 * 由调用方决定怎么展示 —— 客户端不擅自把失败吞成成功。
 */

export class ApiError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body ?? null;
  }
}

/** 把用户可能写错的地址规整一下：去尾斜杠、补 scheme、把 0.0.0.0 换成 localhost。 */
export function normalizeBaseUrl(input) {
  let url = String(input ?? '').trim();
  if (url === '') {
    throw new ApiError('网关地址不能为空');
  }
  if (!/^https?:\/\//i.test(url)) {
    url = `http://${url}`;
  }
  url = url.replace(/\/+$/, '');
  url = url.replace(/\/\/0\.0\.0\.0(?=[:/]|$)/, '//localhost');
  return url;
}

export function createApiClient(baseUrl, { fetchImpl = globalThis.fetch } = {}) {
  const root = normalizeBaseUrl(baseUrl);
  if (typeof fetchImpl !== 'function') {
    throw new ApiError('当前环境没有 fetch，无法发起请求');
  }

  async function request(path, options = {}) {
    let response;
    try {
      response = await fetchImpl(`${root}${path}`, options);
    } catch (cause) {
      // 网络层失败要和后端返回的业务失败区分开
      throw new ApiError(`连不上网关 ${root}：${cause.message}`, { status: 0 });
    }

    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        throw new ApiError(`网关返回的不是 JSON（HTTP ${response.status}）`, {
          status: response.status,
          body: text.slice(0, 200),
        });
      }
    }

    if (!response.ok) {
      throw new ApiError(
        body?.message ?? `HTTP ${response.status}`,
        { status: response.status, body },
      );
    }
    return body;
  }

  return {
    baseUrl: root,
    queryInventory: (sku) => request(`/api/inventory/${encodeURIComponent(sku)}`),
    listInventory: () => request('/api/inventory/_list'),
    placeOrder: (sku, quantity) => request('/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sku, quantity }),
    }),
    listOrders: () => request('/api/orders'),
    setChaos: (enabled) => request(`/api/inventory/chaos?enabled=${enabled ? 'true' : 'false'}`, {
      method: 'POST',
    }),
  };
}

/** 把下单结果翻译成界面要用的语义（颜色 + 标题），三态互不混淆。 */
export function describeOrderResult(data) {
  switch (data?.status) {
    case 'CREATED':
      return { level: 'ok', title: '下单成功', detail: `剩余库存 ${data.remaining}` };
    case 'REJECTED':
      return { level: 'warn', title: '业务拒绝', detail: `原因：${data.reason}` };
    case 'DEGRADED':
      return {
        level: 'error',
        title: '服务降级',
        detail: `上游不可用（${data.errorType ?? data.reason}），订单已记为 DEGRADED`,
      };
    default:
      return { level: 'error', title: '未知结果', detail: `status=${data?.status}` };
  }
}
