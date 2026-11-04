'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),crypto=require('crypto');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-installer-test-'));
process.env.OMARCHY_REMOTE_ROOT=root+'/state';process.env.OMARCHY_REMOTE_HOOK=root+'/hooks/66-remote';
fs.mkdirSync(root+'/hooks');const {run}=require('../iphone-remote/manage');
function bundle(version,fail){
 const dir=root+'/bundle-'+version,stage=root+'/stage-'+version;fs.mkdirSync(dir);fs.mkdirSync(stage);
 fs.writeFileSync(stage+'/server.js',fail?'process.exit(1);':`const fs=require('fs'),http=require('http'),root=process.env.OMARCHY_REMOTE_ROOT;http.createServer((q,r)=>{const c=JSON.parse(fs.readFileSync(root+'/config.json'));if(q.url==='/enable')c.enabled=true;if(q.url==='/disable')c.enabled=false;fs.writeFileSync(root+'/config.json',JSON.stringify(c));r.end(JSON.stringify({installed:true,running:true,paired:true,enabled:c.enabled,version:${JSON.stringify(version)}}));}).listen(18767,'127.0.0.1');process.on('SIGTERM',()=>process.exit(0));`);
 fs.writeFileSync(stage+'/start','#!/bin/sh\necho $$ > "$OMARCHY_REMOTE_ROOT/supervisor.pid"\nexec '+JSON.stringify(process.execPath)+' "$OMARCHY_REMOTE_ROOT/app/server.js"\n',{mode:0o755});
 cp.execFileSync('tar',['-czf',dir+'/bundle.tar.gz','-C',stage,'.']);
 fs.writeFileSync(dir+'/manifest.json',JSON.stringify({version,sha256:crypto.createHash('sha256').update(fs.readFileSync(dir+'/bundle.tar.gz')).digest('hex')}));return dir;
}
test('installer preserves pairings across updates, rollback, disable and removal',async()=>{
 const state=process.env.OMARCHY_REMOTE_ROOT,hook=process.env.OMARCHY_REMOTE_HOOK;
 try{
  assert.equal((await run('status')).installed,false);
  fs.mkdirSync(state);fs.mkdirSync(state+'/persist');fs.writeFileSync(state+'/persist/pairing','existing identity');fs.writeFileSync(state+'/config.json',JSON.stringify({enabled:false,username:'existing',pincode:'private'}));
  const one=bundle('1.0.0'),two=bundle('1.1.0'),bad=bundle('1.2.0',true);
  assert.equal((await run('enable',one)).enabled,true);assert.ok(fs.existsSync(hook));
  assert.equal((await run('sync',two)).version,'1.1.0');assert.equal(JSON.parse(fs.readFileSync(state+'/config.json')).username,'existing');
  await assert.rejects(run('sync',bad),/failed to start/);assert.equal((await run('status')).version,'1.1.0');
  assert.equal((await run('disable',two)).enabled,false);assert.equal((await run('enable',two)).enabled,true);
  fs.appendFileSync(two+'/bundle.tar.gz','broken');await assert.rejects(run('sync',two),/Damaged/);assert.equal((await run('status')).running,true);
  await run('remove');assert.equal(fs.existsSync(hook),false);assert.equal(fs.existsSync(state+'/app'),false);assert.equal(fs.readFileSync(state+'/persist/pairing','utf8'),'existing identity');assert.equal(JSON.parse(fs.readFileSync(state+'/config.json')).enabled,false);
 }finally{await run('remove').catch(()=>{});fs.rmSync(root,{recursive:true,force:true});}
});
