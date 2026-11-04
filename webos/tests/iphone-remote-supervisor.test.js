'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
test('production supervisor survives more than five crashes, keeps one instance, and stops its child',async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-supervisor-'));fs.mkdirSync(root+'/app');
 // Adapt the executable path and shorten retry waits; exercise the real supervisor loop.
 let script=fs.readFileSync(require.resolve('../iphone-remote/start'),'utf8').replace('/usr/bin/node',"'"+process.execPath.replace(/'/g,"'\\''")+"'");
 script=script.replace('sleep "$retry_delay"','sleep 0.05');
 fs.writeFileSync(root+'/app/start',script);
 fs.writeFileSync(root+'/app/server.js',`const fs=require('fs'),r=process.env.OMARCHY_REMOTE_ROOT;let count=fs.existsSync(r+'/count')?+fs.readFileSync(r+'/count'):0;fs.writeFileSync(r+'/count',String(++count));if(count<7){console.log('x'.repeat(70000));process.exit(1);}setInterval(()=>{},1000);`);
 const env={...process.env,OMARCHY_REMOTE_ROOT:root};const child=cp.spawn('sh',[root+'/app/start'],{env,stdio:'ignore'});const exited=new Promise(r=>child.once('exit',r));
 let servicePid;
 try{
  for(let n=0;n<80;n++){if(fs.existsSync(root+'/count')&&+fs.readFileSync(root+'/count')===7)break;await delay(100);}
  assert.equal(+fs.readFileSync(root+'/count'),7);assert.ok(fs.existsSync(root+'/service.log.old'));
  servicePid=+fs.readFileSync(root+'/service.pid');
  await new Promise((resolve,reject)=>cp.execFile('sh',[root+'/app/start'],{env},e=>e?reject(e):resolve()));
  assert.equal(+fs.readFileSync(root+'/service.pid'),servicePid);
  child.kill('SIGTERM');await exited;
  for(let i=0;i<20;i++){try{process.kill(servicePid,0);}catch(e){break;}await delay(50);}
  assert.throws(()=>process.kill(servicePid,0));
 }finally{if(child.exitCode===null)child.kill('SIGTERM');if(servicePid){try{process.kill(servicePid,'SIGKILL');}catch(e){}}fs.rmSync(root,{recursive:true,force:true});}
});
