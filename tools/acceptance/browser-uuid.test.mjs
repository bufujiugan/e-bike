import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import ts from '../../bike-web/node_modules/typescript/lib/typescript.js';

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
for (const app of ['bike-web', 'bike-h5']) {
  const source = readFileSync(new URL(`../../${app}/src/utils/uuid.ts`, import.meta.url), 'utf8');
  const javascript = ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 });
  function load(crypto) {
    const context = vm.createContext({ exports: {}, crypto });
    vm.runInContext(javascript, context);
    return context.exports.createUuid;
  }
  test(`${app}: 优先使用浏览器原生 UUID，保留方法接收者`, () => {
    const crypto = { randomUUID() { assert.equal(this, crypto); return 'native-uuid'; } };
    assert.equal(load(crypto)(), 'native-uuid');
  });
  test(`${app}: HTTP 环境缺少 randomUUID 时生成有效、不重复的 UUID v4`, () => {
    const crypto = { getRandomValues(bytes) { assert.equal(this, crypto); return webcrypto.getRandomValues(bytes); } };
    const createUuid = load(crypto), identifiers = new Set();
    for (let i = 0; i < 10000; i++) {
      const identifier = createUuid();
      assert.match(identifier, uuidPattern);
      identifiers.add(identifier);
    }
    assert.equal(identifiers.size, 10000);
  });
  test(`${app}: 无安全随机源时给出可理解的错误`, () => {
    for (const crypto of [undefined, {}]) assert.throws(load(crypto), /当前浏览器不支持安全随机数/);
  });
}
