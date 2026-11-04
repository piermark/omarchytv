'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),http=require('http'),{EventEmitter}=require('events');
const {createRemoteServer}=require('../iphone-remote/server');
const tick=()=>new Promise(r=>setImmediate(r));
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};}
function fixture(enabled=true,options={}){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-server-'));
 fs.writeFileSync(root+'/control-token','test-token');fs.writeFileSync(root+'/install.json',JSON.stringify({homeId:'test.home'}));
 fs.writeFileSync(root+'/config.json',JSON.stringify({enabled,username:'02:00:00:00:00:01',pincode:'123-45-679',inputIds:{}}));
 const instances=[];let resets=0;
 class Service {setCharacteristic(){return this;}updateCharacteristic(){return this;}setPrimaryService(){}addLinkedService(){}getCharacteristic(){return {onSet(){},onGet(){}};}}
 class Accessory extends EventEmitter{
  constructor(){super();instances.push(this);this._accessoryInfo={paired:()=>true};this.stopped=false;}
  getService(){return new Service();}addService(){return new Service();}setupURI(){return 'test';}
  publish(){this.emit('listening');if(options.autoAdvertise)queueMicrotask(()=>this.emit('advertised'));return Promise.resolve();}
  unpublish(){this.stopped=true;return Promise.resolve();}
  static cleanupAccessoryData(){resets++;}
 }
 const hap={Accessory,Service:{},Characteristic:new Proxy({},{get:(_,n)=>({name:n,RELATIVE:1,ALWAYS_DISCOVERABLE:1})}),Categories:{TELEVISION:31},uuid:{generate:s=>s},HAPStorage:{setCustomStoragePath(){}},HapStatusError:Error,HAPStatus:{}};
 const catalog=options.catalog||Promise.resolve({launchPoints:[]});
 const platform={call:uri=>uri.endsWith('listLaunchPoints')?catalog:Promise.resolve({devices:[]}),key:async()=>{},remote:async()=>{},launch:async()=>{}};
 const app=createRemoteServer({root,hap,platform,qr:{toDataURL:async()=>'test'},port:0,startupTimeoutMs:options.timeout||1000});
 async function request(action,token='test-token'){
  if(!app.server.listening)await new Promise(r=>app.server.once('listening',r));
  return new Promise((resolve,reject)=>{const q=http.request({host:'127.0.0.1',port:app.server.address().port,path:'/'+action,method:action==='status'?'GET':'POST',headers:{'X-Omarchy-Token':token}},r=>{let b='';r.on('data',d=>b+=d);r.on('end',()=>resolve({code:r.statusCode,body:JSON.parse(b)}));});q.on('error',reject);q.end();});
 }
 return {app,root,instances,request,get resets(){return resets;},async cleanup(){await app.close();fs.rmSync(root,{recursive:true,force:true});}};
}
test('boot startup followed by HTTP disable cannot republish after success',async()=>{
 const catalog=deferred(),f=fixture(true,{catalog:catalog.promise});
 try{
  let answered=false;const disabled=f.request('disable').then(r=>{answered=true;return r;});await tick();assert.equal(answered,false);
  catalog.resolve({launchPoints:[]});await tick();await tick();assert.equal(f.instances.length,1);
  f.instances[0].emit('advertised');const response=await disabled;
  assert.equal(response.code,200);assert.equal(response.body.enabled,false);assert.equal(response.body.running,false);assert.equal(f.instances[0].stopped,true);
  f.instances[0].emit('advertised');await tick();assert.equal(f.app.status().running,false);
 }finally{await f.cleanup();}
});
test('overlapping enable and reset share the boot lifecycle',async()=>{
 const catalog=deferred(),f=fixture(true,{catalog:catalog.promise,autoAdvertise:true});
 try{const enabled=f.app.transition('enable'),reset=f.app.transition('reset');catalog.resolve({launchPoints:[]});await Promise.all([enabled,reset]);assert.equal(f.instances.length,2);assert.equal(f.resets,1);assert.equal(f.instances[0].stopped,true);assert.equal(f.app.status().ready,true);}finally{await f.cleanup();}
});
test('publish resolution alone is not readiness; advertisement is required',async()=>{
 const f=fixture();try{await tick();await tick();assert.equal(f.app.status().ready,false);assert.equal(f.app.status().running,false);f.instances[0].emit('advertised');await f.app.boot;assert.equal(f.app.status().initialization,'ready');assert.equal(f.app.status().running,true);assert.equal((await f.request('status','wrong')).code,403);}finally{await f.cleanup();}
});
test('advertisement timeout is a failed initialization and remains manageable',async()=>{
 const f=fixture(true,{timeout:25});try{await assert.rejects(f.app.boot,/advertisement timed out/);const s=f.app.status();assert.equal(s.initialization,'failed');assert.equal(s.ready,false);assert.equal(s.running,false);assert.equal(f.instances[0].stopped,true);assert.equal((await f.request('disable')).body.initialization,'disabled');}finally{await f.cleanup();}
});
test('deliberately disabled initialization is ready without publishing',async()=>{
 const f=fixture(false);try{await f.app.boot;assert.equal(f.app.status().ready,true);assert.equal(f.app.status().initialization,'disabled');assert.equal(f.instances.length,0);}finally{await f.cleanup();}
});
test('shutdown drains startup and rejects queued reset without publishing a replacement',async()=>{
 const catalog=deferred(),f=fixture(true,{catalog:catalog.promise,autoAdvertise:true});
 await tick(); // Boot is already waiting for the catalogue.
 const reset=f.app.transition('reset');const rejected=assert.rejects(reset,/shutting down/);
 const closed=f.app.close();catalog.resolve({launchPoints:[]});
 await Promise.all([closed,rejected]);assert.equal(f.resets,0);assert.equal(f.app.status().running,false);
 fs.rmSync(f.root,{recursive:true,force:true});
});
