import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import ts from '../../bike-web/node_modules/typescript/lib/typescript.js';
import { parse } from '../../bike-web/node_modules/vue/compiler-sfc/index.js';

const source = readFileSync(new URL('../../bike-web/src/components/PiWorkspace.vue', import.meta.url), 'utf8');
const { descriptor } = parse(source);
const syntax = ts.createSourceFile('PiWorkspace.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
const functions = new Set(['documentIssue', 'stepIssue', 'cents', 'fromCents', 'payload']);
const declarations = syntax.statements.filter(statement =>
  ts.isFunctionDeclaration(statement) && functions.has(statement.name?.text)
  || ts.isVariableStatement(statement) && statement.declarationList.declarations.some(declaration => declaration.name.getText(syntax) === 'gateMet')
).map(statement => statement.getText(syntax)).join('\n');
assert.equal(syntax.statements.filter(statement => ts.isFunctionDeclaration(statement) && functions.has(statement.name?.text)).length, functions.size);
const javascript = ts.transpile(declarations, { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS });

function form(shipmentRequiredAmount) {
  return {
    customerId: 1, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '',
    discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00',
    depositRequiredAmount: '30.00', shipmentRequiredAmount, remark: '',
    document: { sellerName: '卖方', bankName: '银行', bankAccount: '账号', beneficiary: '收款人',
      paymentTerms: '按约定付款', deliveryTerms: '约定交付', transportMode: '陆运',
      templateCode: 'TEST', templateVersion: '1', issueDate: '2026-11-11', validUntil: '2026-11-30', shippingAddress: '收货地址' },
    items: [{ skuId: 10, quantity: 1, unitPrice: '100.00', discountAmount: '0.00', taxAmount: '0.00', unit: '台' }],
  };
}

function harness(shipment) {
  const context = vm.createContext({
    draft: form(shipment), preview: { value: { subtotal: 10000n, discount: 0n, total: 10000n } },
    revisionFulfillment: { value: false }, selectedLocks: { value: [] },
    detail: { value: { paidAmount: '30.00', shipmentRequiredAmount: '100.00' } },
    detailProfile: { value: { rules: { paymentMode: 'PREPAID' } } }, workflow: { value: { steps: [] } },
    computed: getter => ({ get value() { return getter(); } }),
  });
  vm.runInContext(javascript, context);
  return { context, evaluate: expression => vm.runInContext(expression, context) };
}

for (const [amount, expected] of [['', '0.00'], ['   ', '0.00'], ['0', '0.00'], ['10.00', '10.00'], ['150.00', '150.00'], ['9999999999999999.99', '9999999999999999.99']]) {
  test('发货金额 ' + JSON.stringify(amount) + ' 可通过向导并保存，不与定金或总额比较', () => {
    const { evaluate } = harness(amount);
    assert.equal(evaluate('stepIssue(1)'), null);
    assert.equal(evaluate('payload().shipmentRequiredAmount'), expected);
    assert.equal(evaluate('payload().depositRequiredAmount'), '30.00');
  });
}

test('选填仍保留金额存储格式校验', () => {
  for (const amount of ['-1.00', '1.001', 'abc', '10000000000000000.00']) {
    const { evaluate } = harness(amount);
    assert.equal(evaluate('stepIssue(1).field'), 'shipment');
    assert.throws(() => evaluate('payload()'), /有效金额/);
  }
});

test('定金的必填及总额校验不受影响', () => {
  const { context, evaluate } = harness('');
  for (const amount of ['', '101.00']) {
    context.draft.depositRequiredAmount = amount;
    assert.equal(evaluate('stepIssue(1).field'), 'deposit');
    assert.throws(() => evaluate('payload()'), /库存保留门槛/);
  }
});

test('历史发货金额不禁用放行入口，后付款仍需授权', () => {
  const { context, evaluate } = harness('');
  for (const amount of [null, '100.00', '9999999999999999.99']) {
    context.detail.value.shipmentRequiredAmount = amount;
    assert.equal(evaluate('gateMet.value'), true);
  }
  context.detailProfile.value.rules.paymentMode = 'CREDIT';
  assert.equal(evaluate('gateMet.value'), false);
  context.workflow.value.steps = [{ code: 'CREDIT_APPROVED', status: 'COMPLETED' }];
  assert.equal(evaluate('gateMet.value'), true);
});

test('发货放行门槛显示为选填且没有必填标记', () => {
  const field = descriptor.template.content.match(/<el-form-item\b[^>]*label="发货放行门槛"[^>]*>([\s\S]*?)<\/el-form-item>/);
  assert.ok(field);
  assert.doesNotMatch(field[0].split('>')[0], /\brequired\b/);
  assert.match(field[1], /placeholder="选填"/);
  assert.match(field[1], /仅作记录，不限制发货放行/);
});
