import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import ts from '../../bike-web/node_modules/typescript/lib/typescript.js';

const context = vm.createContext({ exports: {} });
vm.runInContext(ts.transpile(readFileSync(new URL('../../bike-web/src/pi-sales-scenario.ts', import.meta.url), 'utf8'),
  { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }), context);
const { salesScenarioDefaults, salesScenarioFilters, matchesSalesScenario } = context.exports;

test('国内外销和捷克内外销分别使用正确的贸易类型、出货属性和币种默认值', () => {
  for (const [scenario, tradeType, domesticShipment, currencyCode] of [
    ['CHINA_EXPORT', 'EXPORT', true, 'USD'],
    ['CZECH_DOMESTIC', 'DOMESTIC', false, 'EUR'],
    ['CZECH_EXPORT', 'EXPORT', false, 'EUR'],
  ]) {
    const defaults = salesScenarioDefaults(scenario);
    assert.equal(defaults.tradeType, tradeType);
    assert.equal(defaults.domesticShipment, domesticShipment);
    assert.equal(defaults.currencyCode, currencyCode);
    assert.deepEqual(JSON.parse(JSON.stringify(salesScenarioFilters(scenario))), { tradeType, salesChannel: scenario === 'CHINA_EXPORT' ? 'CHINA_SALES' : 'CZECH_PI' });
    assert.equal(matchesSalesScenario({ tradeType, document: { business: { domesticShipment, salesChannel: scenario === 'CHINA_EXPORT' ? 'CHINA_SALES' : 'CZECH_PI' } } }, scenario), true);
  }
});

test('草稿不会跨内外销或国内捷克入口恢复，历史缺失属性的草稿仍可从历史入口恢复', () => {
  const china = { tradeType: 'EXPORT', document: { business: { domesticShipment: true, salesChannel: 'CHINA_SALES' } } };
  const czech = { tradeType: 'DOMESTIC', document: { business: { domesticShipment: false, salesChannel: 'CZECH_PI' } } };
  const legacy = { tradeType: 'DOMESTIC', document: {} };
  assert.equal(matchesSalesScenario(china, 'CZECH_EXPORT'), false);
  assert.equal(matchesSalesScenario(czech, 'CZECH_EXPORT'), false);
  assert.equal(matchesSalesScenario(czech, 'CHINA_EXPORT'), false);
  assert.equal(matchesSalesScenario(legacy, 'CZECH_DOMESTIC'), true);
  assert.equal(matchesSalesScenario(legacy), true);
  assert.deepEqual(JSON.parse(JSON.stringify(salesScenarioFilters())), {});
});

test('出货地点不改变销售入口，捷克 PI 可按实际业务从国内出货', () => {
  const czech = { tradeType: 'EXPORT', document: { business: { domesticShipment: true, salesChannel: 'CZECH_PI' } } };
  assert.equal(matchesSalesScenario(czech, 'CZECH_EXPORT'), true);
  assert.equal(matchesSalesScenario(czech, 'CHINA_EXPORT'), false);
});
