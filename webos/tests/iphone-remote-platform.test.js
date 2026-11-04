'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {createPlatform}=require('../iphone-remote/platform');
test('Home, Back, directions and playback reach system services',async()=>{
 const calls=[];const p=createPlatform(async(uri,data)=>{calls.push({uri,data});return {returnValue:true,foregroundAppInfo:[{playState:'playing',type:'media'}]};});
 await p.remote(10);assert.equal(calls.pop().data.id,'com.omarchytv.home');
 await p.remote(9);assert.equal(calls.pop().data.keyCode,412);
 await p.remote(7);assert.equal(calls.pop().data.keyCode,106);
 await p.remote(11);assert.equal(calls.pop().data.keyCode,119);
 await assert.rejects(p.remote(99),/Unsupported/);
 await assert.rejects(p.key('arbitrary'),/Unknown/);
});
test('rapid key presses retain order and recover after service failure',async()=>{
 const sent=[];let fail=true;
 const p=createPlatform(async(uri,data)=>{sent.push(data.keyCode);await new Promise(r=>setTimeout(r,5));if(fail){fail=false;throw Error('failed');}return {returnValue:true};});
 const result=await Promise.allSettled([p.key('up'),p.key('down'),p.key('select')]);
 assert.deepEqual(sent,[103,108,28]);assert.deepEqual(result.map(r=>r.status),['rejected','fulfilled','fulfilled']);
});
test('unknown playback state resumes instead of guessing pause',async()=>{
 const calls=[];const p=createPlatform(async(uri,data)=>{calls.push(data);return {returnValue:true,foregroundAppInfo:[]};});
 await p.remote(11);assert.equal(calls.pop().keyCode,207);
});
