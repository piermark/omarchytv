'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-installer-test-'));
process.env.OMARCHY_REMOTE_ROOT=root+'/state';process.env.OMARCHY_REMOTE_HOOK=root+'/hooks/66-remote';
fs.mkdirSync(root+'/hooks');const {run,readiness}=require('../iphone-remote/manage');
const hap=require('../iphone-remote/node_modules/hap-nodejs');
const {AccessoryInfo}=require('../iphone-remote/node_modules/hap-nodejs/dist/lib/model/AccessoryInfo');
function bundle(version,fail){
 const dir=root+'/bundle-'+version,stage=root+'/stage-'+version;fs.mkdirSync(dir);fs.mkdirSync(stage);
 fs.writeFileSync(stage+'/server.js',`const fs=require('fs'),http=require('http'),root=process.env.OMARCHY_REMOTE_ROOT;http.createServer((q,r)=>{const c=JSON.parse(fs.readFileSync(root+'/config.json'));if(q.url==='/enable')c.enabled=true;if(q.url==='/disable')c.enabled=false;fs.writeFileSync(root+'/config.json',JSON.stringify(c));r.end(JSON.stringify({installed:true,running:${!fail}&&c.enabled,ready:${!fail},initialization:${fail?"'failed'":"c.enabled?'ready':'disabled'"},startupError:${JSON.stringify(fail?'TV service unavailable':'')},paired:true,enabled:c.enabled,version:${JSON.stringify(version)}}));}).listen(18767,'127.0.0.1');process.on('SIGTERM',()=>process.exit(0));`);
 fs.writeFileSync(stage+'/start','#!/bin/sh\necho $$ > "$OMARCHY_REMOTE_ROOT/supervisor.pid"\nexec '+JSON.stringify(process.execPath)+' "$OMARCHY_REMOTE_ROOT/app/server.js"\n',{mode:0o755});
 cp.execFileSync('tar',['-czf',dir+'/bundle.tar.gz','-C',stage,'.']);
 fs.writeFileSync(dir+'/manifest.json',JSON.stringify({version,sha256:crypto.createHash('sha256').update(fs.readFileSync(dir+'/bundle.tar.gz')).digest('hex')}));return dir;
}
test('installer preserves HAP pairing through updates, failed initialization, disable and removal',async()=>{
 const state=process.env.OMARCHY_REMOTE_ROOT,hook=process.env.OMARCHY_REMOTE_HOOK;
 try{
  assert.equal((await run('status')).installed,false);
  fs.mkdirSync(state);fs.mkdirSync(state+'/persist');
  hap.HAPStorage.setCustomStoragePath(state+'/persist');const identity=AccessoryInfo.create('02:00:00:00:00:AA');identity.addPairedClient('fixture-phone',Buffer.alloc(32,7),1);identity.save();
  fs.writeFileSync(state+'/config.json',JSON.stringify({enabled:false,username:'existing',pincode:'private'}));
  const one=bundle('1.0.0'),two=bundle('1.1.0'),bad=bundle('1.2.0',true);
  assert.equal((await run('enable',one)).enabled,true);assert.ok(fs.existsSync(hook));
  assert.equal((await run('sync',two)).version,'1.1.0');assert.equal(JSON.parse(fs.readFileSync(state+'/config.json')).username,'existing');
  assert.equal(AccessoryInfo.load('02:00:00:00:00:AA').paired(),true);
  const holder=cp.spawn('flock',[state+'/install.lock','sh','-c','echo locked; sleep 0.3']);await new Promise(r=>holder.stdout.once('data',r));
  let finished=false;const cli=new Promise((resolve,reject)=>cp.execFile(process.execPath,[require.resolve('../iphone-remote/manage'),'status'],{env:process.env},(e,out)=>{finished=true;e?reject(e):resolve(JSON.parse(out));}));
  await new Promise(r=>setTimeout(r,60));assert.equal(finished,false,'production CLI waits for the installation lock');assert.equal((await cli).version,'1.1.0');
  await assert.rejects(run('sync',bad),/TV service unavailable/);assert.equal((await run('status')).version,'1.1.0');
  assert.equal((await run('disable',two)).enabled,false);assert.equal((await run('enable',two)).enabled,true);
  fs.appendFileSync(two+'/bundle.tar.gz','broken');await assert.rejects(run('sync',two),/Damaged/);assert.equal((await run('status')).running,true);
  assert.equal((await run('disable',two)).enabled,false,'corrupt bundle cannot prevent disable');
  await run('remove');assert.equal(fs.existsSync(hook),false);assert.equal(fs.existsSync(state+'/app'),false);
  assert.equal(AccessoryInfo.load('02:00:00:00:00:AA').paired(),true);assert.deepEqual(AccessoryInfo.load('02:00:00:00:00:AA').getClientPublicKey('fixture-phone'),Buffer.alloc(32,7));
  assert.equal(JSON.parse(fs.readFileSync(state+'/config.json')).enabled,false);
 }finally{await run('remove').catch(()=>{});fs.rmSync(root,{recursive:true,force:true});}
});
test('readiness rejects broken enabled service and accepts deliberate disabled state',()=>{
 assert.throws(()=>readiness({version:'1.1.2',enabled:true,running:false,initialization:'failed',startupError:'failed HAP'},'1.1.2'),/failed HAP/);
 assert.equal(readiness({version:'1.1.2',enabled:true,running:false,initialization:'starting',ready:false},'1.1.2'),false);
 assert.equal(readiness({version:'1.1.2',enabled:false,running:false,initialization:'disabled',ready:true},'1.1.2'),true);
});
