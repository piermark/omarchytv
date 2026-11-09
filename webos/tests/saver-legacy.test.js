const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const runtime = fs.readFileSync(path.join(root, 'webos/tools/saver-build/legacy-runtime.js'), 'utf8');
const bundle = fs.readFileSync(path.join(root, 'launcher/app/src/main/assets/saver/tte-bundle.js'), 'utf8');
test('legacy canvas overlay has explicit offsets instead of relying on CSS inset', () => {
  const html = fs.readFileSync(path.join(root, 'webos/app/saver/index.html'), 'utf8');
  assert.match(html, /\[data-text-effect\]>canvas\{top:0;left:0;width:100%!important;height:100%!important\}/);
});
function legacy() {
  const context = vm.createContext({});
  vm.runInContext('window=this; Object.assign=undefined; Array.from=undefined; String.prototype.padStart=undefined; function Element(){this.children=[];} Element.prototype.appendChild=function(n){this.children.push(n);}; document={createTextNode:function(t){return {text:t};}};', context);
  return context;
}
test('bundle boots and exposes all 37 effects without modern runtime APIs', () => {
  const context = legacy();
  vm.runInContext(bundle, context);
  assert.equal(vm.runInContext('typeof TTE.TextEffect', context), 'function');
  assert.equal(vm.runInContext('TTE.effectNames().length', context), 37);
  assert.ok(vm.runInContext('TTE.effectNames().indexOf("decrypt") >= 0', context));
});
test('Array.from supports strings, mapped arrays and Map/Set iterators', () => {
  const context = legacy(); vm.runInContext(runtime, context);
  assert.equal(vm.runInContext('Array.from("A😀").length', context), 2);
  assert.equal(vm.runInContext('Array.from(new Map([["a",1]]).keys()).join("")', context), 'a');
  assert.equal(vm.runInContext('Array.from(new Set([2,3]),function(x){return x*2;}).join(",")', context), '4,6');
});
test('DOM append and style/color helpers work without replacing native APIs', () => {
  const context = legacy(); vm.runInContext(runtime, context);
  assert.equal(vm.runInContext('"f".padStart(2,"0")', context), '0f');
  assert.equal(vm.runInContext('Object.assign({},null,{color:"gold"}).color', context), 'gold');
  assert.equal(vm.runInContext('var e=new Element();e.append("text",{node:true});e.children.length', context), 2);
  assert.equal(vm.runInContext('e.children[0].text', context), 'text');
  assert.equal(vm.runInContext('var original=Object.assign;'+runtime+';original===Object.assign', context), true);
});
