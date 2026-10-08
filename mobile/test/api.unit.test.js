import assert from 'node:assert/strict';
import test from 'node:test';

import { ApiError, createApiClient, describeOrderResult, normalizeBaseUrl } from '../www/api.js';

/** 造一个假的 fetch，用来在不起后端的情况下验客户端自身的解析与错误处理。 */
function fakeFetch(handler) {
  const calls = [];
  const impl = async (url, options) => {
    calls.push({ url, options });
    const result = handler(url, options);
    return {
      ok: result.status >= 200 && result.status < 300,
      status: result.status,
      text: async () => (result.body === undefined ? '' : JSON.stringify(result.body)),
    };
  };
  impl.calls = calls;
  return impl;
}

test('normalizeBaseUrl 容忍用户少写 scheme / 多写斜杠', () => {
  assert.equal(normalizeBaseUrl('10.0.2.2:8080'), 'http://10.0.2.2:8080');
  assert.equal(normalizeBaseUrl('http://localhost:8080///'), 'http://localhost:8080');
  assert.equal(normalizeBaseUrl(' https://gw.example.com '), 'https://gw.example.com');
  assert.throws(() => normalizeBaseUrl('   '), ApiError);
});

test('queryInventory 命中正确的路径，并原样返回 ApiResponse 外壳', async () => {
  const fetchImpl = fakeFetch(() => ({
    status: 200,
    body: { ok: true, code: 'OK', message: 'success', data: { sku: 'MONITOR', available: 42, reserved: 0, lowStock: false } },
  }));
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  const res = await client.queryInventory('MONITOR');

  assert.equal(fetchImpl.calls[0].url, 'http://localhost:8080/api/inventory/MONITOR');
  assert.equal(res.ok, true);
  assert.equal(res.data.available, 42);
});

test('SKU 里的特殊字符会被 URL 编码，不会拼出坏路径', async () => {
  const fetchImpl = fakeFetch(() => ({ status: 200, body: { ok: true, data: {} } }));
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  await client.queryInventory('A/B C');

  assert.equal(fetchImpl.calls[0].url, 'http://localhost:8080/api/inventory/A%2FB%20C');
});

test('placeOrder 发 POST 且带 JSON body', async () => {
  const fetchImpl = fakeFetch(() => ({
    status: 200,
    body: { ok: true, code: 'OK', data: { orderNo: 'ORD-1', status: 'CREATED', reason: 'OK', remaining: 97 } },
  }));
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  await client.placeOrder('MONITOR', 3);

  const call = fetchImpl.calls[0];
  assert.equal(call.url, 'http://localhost:8080/api/orders');
  assert.equal(call.options.method, 'POST');
  assert.equal(call.options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(call.options.body), { sku: 'MONITOR', quantity: 3 });
});

test('网络不通 → ApiError(status=0)，与后端的业务失败区分开', async () => {
  const fetchImpl = async () => {
    throw new TypeError('Failed to fetch');
  };
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  await assert.rejects(() => client.queryInventory('X'), (err) => {
    assert.ok(err instanceof ApiError);
    assert.equal(err.status, 0);
    assert.match(err.message, /连不上网关/);
    return true;
  });
});

test('后端返回错误码时抛 ApiError 并带上原始 body，不吞失败', async () => {
  const fetchImpl = fakeFetch(() => ({
    status: 400,
    body: { ok: false, code: 'INVALID_REQUEST', message: 'quantity must be greater than 0', data: null },
  }));
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  await assert.rejects(() => client.placeOrder('MONITOR', 0), (err) => {
    assert.equal(err.status, 400);
    assert.equal(err.body.code, 'INVALID_REQUEST');
    return true;
  });
});

test('非 JSON 响应会被明确指出（而不是抛一个看不懂的解析错误）', async () => {
  const fetchImpl = async () => ({ ok: true, status: 200, text: async () => '<html>gateway</html>' });
  const client = createApiClient('http://localhost:8080', { fetchImpl });

  await assert.rejects(() => client.listOrders(), /不是 JSON/);
});

test('describeOrderResult 把三态映射成不同颜色，不混淆', () => {
  assert.equal(describeOrderResult({ status: 'CREATED' }).level, 'ok');
  assert.equal(describeOrderResult({ status: 'REJECTED' }).level, 'warn');
  assert.equal(describeOrderResult({ status: 'DEGRADED' }).level, 'error');
  assert.equal(describeOrderResult({ status: 'WHATEVER' }).level, 'error');
});
