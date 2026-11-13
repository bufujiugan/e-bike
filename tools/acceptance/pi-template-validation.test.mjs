import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import ts from '../../bike-web/node_modules/typescript/lib/typescript.js';
import { parse } from '../../bike-web/node_modules/vue/compiler-sfc/index.js';
import { computed, nextTick, reactive, ref } from '../../bike-web/node_modules/vue/dist/vue.runtime.esm-bundler.js';

const source = readFileSync(new URL('../../bike-web/src/components/PiTemplates.vue', import.meta.url), 'utf8');
const { descriptor } = parse(source);
const syntax = ts.createSourceFile('PiTemplates.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true);
const script = syntax.statements.filter(statement => !ts.isImportDeclaration(statement)).map(statement => statement.getText(syntax)).join('\n');
const javascript = ts.transpile(script, { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS });

function harness() {
  const calls = { confirms: 0, publishes: 0, focus: [], scroll: [] };
  const context = vm.createContext({
    computed, nextTick, reactive, ref, watch: () => {},
    defineProps: () => ({ canManage: true }), defineEmits: () => () => {},
    isSelectablePiLayout: () => true, piTemplateLayouts: [], piLayoutLabel: value => value,
    ElMessage: { success: () => {} },
    ElMessageBox: { confirm: async () => { calls.confirms++; } },
    api: { isAxiosError: error => Boolean(error?.response) },
    piApi: {
      templates: async () => ({ records: [], total: 0 }),
      publishTemplate: async draft => { calls.publishes++; if (calls.failure) throw calls.failure; return draft; },
    },
    formElement: { querySelector: selector => ({
      scrollIntoView: () => calls.scroll.push(selector),
      querySelector: () => ({ focus: () => calls.focus.push(selector) }),
    }) },
  });
  vm.runInContext(javascript, context);
  const run = code => vm.runInContext(code, context);
  run(`templateForm.value = { $el: formElement }; beginAdjustment();
    for (const [key] of fields) draft[key] = '有效资料';
    draft.templateCode = 'EXPORT_01'; draft.templateVersion = 'V1.0'; draft.sellerEmail = 'sales@example.com';`);
  return { calls, run };
}

test('中文模板编码在确认和请求之前拦截，并聚焦具体字段', async () => {
  const { calls, run } = harness();
  run("draft.templateCode = '你写1'");
  await run('publish()');
  assert.match(run('fieldErrors.templateCode'), /模板编码.*EXPORT_01/);
  assert.equal(calls.confirms, 0);
  assert.equal(calls.publishes, 0);
  assert.deepEqual(calls.focus, ['[data-template-field="templateCode"]']);
  assert.deepEqual(calls.scroll, calls.focus);
});

test('同时列出漏填和格式错误，修改后逐项清除', async () => {
  const { run } = harness();
  run("draft.sellerAddress = ' '; draft.bankAccount = ''; draft.sellerEmail = 'bad@@example.com'");
  await run('publish()');
  assert.equal(run('invalidFields.value.length'), 3);
  assert.equal(run('fieldErrors.sellerAddress'), '请填写卖方地址');
  assert.equal(run('fieldErrors.bankAccount'), '请填写收款账号');
  assert.match(run('fieldErrors.sellerEmail'), /卖方Email/);
  run("draft.bankAccount = '123'; validateField('bankAccount')");
  assert.equal(run('invalidFields.value.length'), 2);
});

test('编码与版本边界校验，合法资料正常保存', async () => {
  const { run, calls } = harness();
  for (const value of ['abc', '_ABC', 'A B', 'A'.repeat(65)]) {
    run(`draft.templateCode = ${JSON.stringify(value)}`);
    assert.match(run("fieldIssue('templateCode')"), /模板编码/);
  }
  run("draft.templateCode = 'A'.repeat(64); draft.templateVersion = 'V1.0_test-2'");
  await run('publish()');
  assert.equal(calls.confirms, 1);
  assert.equal(calls.publishes, 1);
  assert.equal(run('editing.value'), false);
});

test('原版本和服务端重复版本都定位版本号，输入内容保留', async () => {
  const { run, calls } = harness();
  run("sourceTemplate.value = { templateVersion: 'V1.0' }");
  await run('publish()');
  assert.match(run('fieldErrors.templateVersion'), /不能与原版本相同/);
  assert.equal(calls.publishes, 0);
  run("draft.templateVersion = 'V2'");
  calls.failure = { response: { data: { code: 'PI_TEMPLATE_EXISTS', message: '此模板版本已发布，请使用新版本号' } } };
  await run('publish()');
  assert.match(run('fieldErrors.templateVersion'), /版本号.*已发布/);
  assert.equal(calls.focus.at(-1), '[data-template-field="templateVersion"]');
  assert.equal(run('draft.templateVersion'), 'V2');
  assert.equal(run('editing.value'), true);
  run("draft.templateVersion = 'V3'; validateField('templateVersion')");
  assert.equal(run('fieldErrors.templateVersion'), undefined);
  assert.equal(run('formError.value'), '');
});

test('服务端字段提示映射到字段，重新打开时清除旧错误', async () => {
  const { run, calls } = harness();
  calls.failure = { response: { data: { code: 'INVALID_PARAMETER', message: '卖方Email：请填写有效的邮箱地址' } } };
  await run('publish()');
  assert.equal(calls.focus.at(-1), '[data-template-field="sellerEmail"]');
  run('beginAdjustment()');
  assert.equal(run('invalidFields.value.length'), 0);
  assert.equal(run('formError.value'), '');
});
