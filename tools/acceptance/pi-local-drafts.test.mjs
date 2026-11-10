import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import ts from '../../bike-web/node_modules/typescript/lib/typescript.js';

const source = readFileSync(new URL('../../bike-web/src/pi-local-drafts.ts', import.meta.url), 'utf8');
const context = vm.createContext({ exports: {} });
vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }), context);
const { createPiLocalDraftStore } = context.exports;
class MemoryStorage {
  entries = new Map();
  get length() { return this.entries.size; }
  key(index) { return [...this.entries.keys()][index] ?? null; }
  getItem(key) { return this.entries.get(key) ?? null; }
  setItem(key, value) { this.entries.set(key, value); }
  removeItem(key) { this.entries.delete(key); }
}
function incompleteDraft() {
  const document = Object.fromEntries(['sellerName', 'sellerAddress', 'sellerContact', 'buyerAddress', 'buyerContact', 'bankName', 'bankAccount', 'beneficiary', 'paymentTerms', 'deliveryTerms', 'templateCode', 'templateVersion'].map(key => [key, '']));
  return {
    form: { tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '填写到一半', document, items: [{ key: 'row', quantity: 1, unitPrice: '', discountAmount: '0.00', taxAmount: '0.00', unit: '台' }] },
    step: 0, editingId: null, fromLocks: false, selectedLocks: [], options: { customers: [], agents: [], skus: [], orders: [] }, baseline: '', baseUpdatedAt: null, customerLoadFailed: false, conversionKey: '', conversionFingerprint: '',
  };
}
test('未选客户、SKU、履约配置和金额的草稿可保存并跨实例恢复', () => {
  const storage = new MemoryStorage();
  createPiLocalDraftStore(storage, 'alice').save('one', incompleteDraft(), null);
  const restored = createPiLocalDraftStore(storage, 'alice').read('one');
  assert.equal(restored.content.form.remark, '填写到一半');
  assert.equal(restored.content.form.items[0].unitPrice, '');
  assert.equal(restored.content.form.customerId, undefined);
});
test('账号隔离，账号名包含冒号时也不能匹配其他账号', () => {
  const storage = new MemoryStorage();
  createPiLocalDraftStore(storage, 'alice').save('one', incompleteDraft(), null);
  for (const username of ['bob', 'alice:team']) assert.equal(createPiLocalDraftStore(storage, username).list().records.length, 0);
  assert.throws(() => createPiLocalDraftStore(storage, ''), /账号/);
});
test('多份草稿独立保留，删除只影响明确选中的记录', () => {
  const store = createPiLocalDraftStore(new MemoryStorage(), 'alice');
  const one = store.save('one', incompleteDraft(), null);
  store.save('two', incompleteDraft(), null);
  store.remove(one);
  assert.equal(store.read('one'), null);
  assert.equal(store.list().records.length, 1);
});
test('恢复完整填写进度、来源锁定和转换幂等标识', () => {
  const store = createPiLocalDraftStore(new MemoryStorage(), 'alice');
  const content = incompleteDraft();
  Object.assign(content, { step: 2, fromLocks: true, selectedLocks: [{ id: 7, skuId: 8, quantity: 4 }], conversionKey: 'request-id', conversionFingerprint: 'fingerprint' });
  content.form.items[0].unitPrice = '9007199254740993.25';
  store.save('one', content, null);
  assert.equal(JSON.stringify(store.read('one').content), JSON.stringify(content));
});
test('其他页面更新或删除后，旧页面不能覆盖或删除最新草稿', () => {
  const storage = new MemoryStorage(), first = createPiLocalDraftStore(storage, 'alice'), second = createPiLocalDraftStore(storage, 'alice');
  const saved = first.save('one', incompleteDraft(), null);
  const changed = incompleteDraft(); changed.form.remark = '另一页面更新';
  const latest = second.save('one', changed, saved.serialized);
  assert.throws(() => first.save('one', incompleteDraft(), saved.serialized), /其他页面修改/);
  assert.throws(() => first.remove(saved), /其他页面修改/);
  second.remove(latest);
  assert.throws(() => first.save('one', incompleteDraft(), saved.serialized), /其他页面修改/);
});
test('损坏的草稿保留原文并报告错误，不影响其他草稿读取', () => {
  const storage = new MemoryStorage(), store = createPiLocalDraftStore(storage, 'alice');
  store.save('good', incompleteDraft(), null);
  const saved = store.save('broken', incompleteDraft(), null);
  const key = [...storage.entries.keys()].find(key => key.endsWith(':broken'));
  for (const invalid of ['{', saved.serialized.replace('"step":0', '"step":99'), saved.serialized.replace('"document":{', '"document":{"buyerEmail":42,')]) {
    storage.setItem(key, invalid);
    const result = store.list();
    assert.equal(result.records.length, 1);
    assert.equal(result.errors.length, 1);
    assert.equal(storage.getItem(key), invalid);
  }
});
test('浏览器拒绝写入时上抛错误，已存草稿仍然保留', () => {
  const storage = new MemoryStorage(), store = createPiLocalDraftStore(storage, 'alice');
  const saved = store.save('one', incompleteDraft(), null);
  storage.setItem = () => { throw new Error('QuotaExceededError'); };
  assert.throws(() => store.save('one', incompleteDraft(), saved.serialized), /QuotaExceededError/);
  assert.equal(store.read('one').serialized, saved.serialized);
});
test('暂时清空日期、商品数量和来源锁定数量仍能恢复', () => {
  const store = createPiLocalDraftStore(new MemoryStorage(), 'alice'), content = incompleteDraft();
  content.form.plannedShipDate = null;
  content.form.items[0].quantity = undefined;
  content.selectedLocks = [{ id: 7, skuId: 8, quantity: undefined }];
  store.save('one', content, null);
  assert.equal(store.list().records.length, 1);
  assert.equal(store.read('one').content.selectedLocks[0].id, 7);
});
test('不能恢复的结构不覆盖之前保存的草稿', () => {
  const store = createPiLocalDraftStore(new MemoryStorage(), 'alice'), content = incompleteDraft();
  const saved = store.save('one', content, null);
  content.form.document.buyerEmail = 42;
  assert.throws(() => store.save('one', content, saved.serialized), /格式异常/);
  assert.equal(store.read('one').serialized, saved.serialized);
});
