/**
 * 真实链路测试：用**App 自己的** API 模块去调**真实运行中的**微服务系统。
 *
 * 这是本项目 Android 侧能做到的最强验证 —— 它证明「客户端逻辑 ↔ 网关 ↔ 订单 ↔ 库存」
 * 真的接通了，而不是只证明「APK 能编译出来」。
 *
 * 前置：bash scripts/run-all.sh  （没起服务时本文件会明确报错，不会假装通过）
 * 可用 GATEWAY_URL 覆盖网关地址。
 */
import assert from 'node:assert/strict';
import test, { before } from 'node:test';

import { createApiClient, describeOrderResult } from '../www/api.js';

const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8080';
let client;

before(async () => {
  client = createApiClient(GATEWAY_URL);
  try {
    await client.queryInventory('MONITOR');
  } catch (err) {
    throw new Error(
      `连不上网关 ${GATEWAY_URL}（${err.message}）。请先执行：bash scripts/run-all.sh`,
    );
  }
});

test('App 客户端能经网关查到库存', async () => {
  const res = await client.queryInventory('MONITOR');

  assert.equal(res.ok, true);
  assert.equal(res.data.sku, 'MONITOR');
  assert.ok(Number.isInteger(res.data.available));
});

test('App 客户端能经网关下单，且库存真的减少（相对断言，可重复跑）', async () => {
  const before = (await client.queryInventory('MONITOR')).data.available;

  const order = await client.placeOrder('MONITOR', 2);

  assert.equal(order.data.status, 'CREATED');
  assert.equal(order.data.remaining, before - 2);

  const after = (await client.queryInventory('MONITOR')).data.available;
  assert.equal(after, before - 2, '库存没有真的减少，怀疑假成功');
});

test('库存不足时客户端拿到 REJECTED，并被界面正确映射为「业务拒绝」而不是错误', async () => {
  const order = await client.placeOrder('PEAR', 4);

  assert.equal(order.data.status, 'REJECTED');
  assert.equal(order.data.reason, 'INSUFFICIENT_STOCK');
  assert.equal(describeOrderResult(order.data).level, 'warn');
});

test('打开故障注入后，客户端拿到 DEGRADED（熔断降级），界面映射为错误级', async () => {
  await client.setChaos(true);
  try {
    const results = [];
    for (let i = 0; i < 5; i += 1) {
      const r = await client.placeOrder('MONITOR', 1);
      results.push(r.data);
    }

    assert.ok(results.every((d) => d.status === 'DEGRADED'), '存在非 DEGRADED 的结果');
    assert.ok(results.every((d) => d.reason === 'UPSTREAM_UNAVAILABLE'));
    assert.ok(
      results.some((d) => d.errorType === 'CallNotPermittedException'),
      `熔断器没有真的打开，观察到的 errorType：${results.map((d) => d.errorType).join(',')}`,
    );
    assert.equal(describeOrderResult(results.at(-1)).level, 'error');
  } finally {
    await client.setChaos(false);
    // 熔断器打开后会保持 wait-duration-in-open-state（配置为 10s）。这里等它转半开，
    // 否则下一个用例会撞上一只还开着的熔断器而误判失败（脚本必须可重复跑）。
    await new Promise((resolve) => setTimeout(resolve, 11_000));
  }
});

test('单笔上限由配置中心决定：15 件放行、超上限被业务拒绝', async () => {
  // 上限 20 来自配置中心（本地兜底是 1）。15 件能过 → 证明拿到的不是兜底值
  assert.equal((await client.placeOrder('MONITOR', 15)).data.status, 'CREATED');

  // 超上限属于**业务拒绝**：HTTP 仍是 200，但 status=REJECTED + reason=INVALID_REQUEST。
  // 只有结构性非法（quantity <= 0，被 @Min(1) 挡下）才是 HTTP 400 —— 两者要分清楚
  const over = await client.placeOrder('MONITOR', 25);

  assert.equal(over.data.status, 'REJECTED');
  assert.equal(over.data.reason, 'INVALID_REQUEST');
});

test('结构性非法数量（0）→ HTTP 400，与「超业务上限的 200」区分开', async () => {
  await assert.rejects(
    () => client.placeOrder('MONITOR', 0),
    (err) => {
      assert.equal(err.status, 400);
      assert.equal(err.body.code, 'INVALID_REQUEST');
      return true;
    },
  );
});
