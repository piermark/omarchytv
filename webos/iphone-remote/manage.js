#!/usr/bin/env node
'use strict';
// Executed locally by Homebrew Channel. Never exposed as a network command endpoint.
const fs=require('fs'),path=require('path'),crypto=require('crypto'),cp=require('child_process'),http=require('http');
const ROOT=process.env.OMARCHY_REMOTE_ROOT||'/var/lib/omarchytv-remote';
const HOOK=process.env.OMARCHY_REMOTE_HOOK||'/var/lib/webosbrew/init.d/66-omarchytv-remote';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
function shellQuote(s){return "'"+s.replace(/'/g,"'\\''")+"'";}
function read(file,fallback){try{return JSON.parse(fs.readFileSync(file,'utf8'));}catch(e){if(e.code==='ENOENT')return fallback;throw e;}}
function atomic(file,value){fs.mkdirSync(path.dirname(file),{recursive:true,mode:0o700});const tmp=file+'.new';fs.writeFileSync(tmp,JSON.stringify(value)+'\n',{mode:0o600});fs.renameSync(tmp,file);}
function request(action){return new Promise((resolve,reject)=>{let token;try{token=fs.readFileSync(ROOT+'/control-token','utf8').trim();}catch(e){return reject(e);}const req=http.request({hostname:'127.0.0.1',port:Number(process.env.OMARCHY_REMOTE_PORT||18767),path:'/'+action,method:action==='status'?'GET':'POST',headers:{'X-Omarchy-Token':token},timeout:action==='status'?2500:60000},res=>{let b='';res.on('data',d=>{b+=d;if(b.length>131072)req.destroy(Error('Oversized response'));});res.on('end',()=>{try{const r=JSON.parse(b);if(res.statusCode!==200)throw Error(r.error||'Remote request failed');resolve(r);}catch(e){reject(e);}});});req.on('error',reject);req.on('timeout',()=>req.destroy(Error('Remote service timed out')));req.end();});}
function owns(pid){try{return fs.readFileSync('/proc/'+pid+'/cmdline','utf8').includes(ROOT+'/');}catch(e){return false;}}
async function stop(){
 let pids=[];for(const f of ['supervisor.pid','service.pid']){try{const p=Number(fs.readFileSync(ROOT+'/'+f,'utf8'));if(p>1&&owns(p))pids.push(p);}catch(e){}}
 // Migrate the first standalone build, whose supervisor did not write a pid file.
 for(const pid of pids.slice()){try{const s=fs.readFileSync('/proc/'+pid+'/status','utf8');const parent=Number(s.match(/^PPid:\s+(\d+)/m)[1]);if(parent>1&&owns(parent))pids.unshift(parent);}catch(e){}}
 pids=[...new Set(pids)];for(const pid of pids){try{process.kill(pid,'SIGTERM');}catch(e){if(e.code!=='ESRCH')throw e;}}
 // Startup phases are bounded, but graceful shutdown can wait behind startup.
 for(let n=0;n<600&&pids.some(owns);n++)await delay(100);
 for(const pid of pids.filter(owns)){try{process.kill(pid,'SIGKILL');}catch(e){if(e.code!=='ESRCH')throw e;}}
 for(let n=0;n<20&&pids.some(owns);n++)await delay(100);
 if(pids.some(owns))throw Error('Remote did not stop; installation left unchanged');
}
function readiness(s,version,legacy=false){
 if(version&&s.version!==version)throw Error('Unexpected remote service version');
 if(s.initialization==='failed'||s.startupError)throw Error(s.startupError||'HomeKit startup failed');
 if(s.ready===true&&((s.enabled&&s.running&&s.initialization==='ready')||(!s.enabled&&!s.running&&s.initialization==='disabled')))return true;
 // Only used to restore the original standalone release during migration rollback.
 if(legacy&&s.initialization===undefined&&!s.lastError&&s.running===!!s.enabled)return true;
 return false;
}
async function waitReady(version,legacy=false){
 const deadline=Date.now()+45000;let last;
 while(Date.now()<deadline){
   let s;
   try{s=await request('status');}catch(e){last=e;await delay(200);continue;}
   if(readiness(s,version,legacy))return s;
   await delay(200);
 }
 throw Error('Remote service failed to start'+(last?': '+last.message:''));
}
async function start(version,legacy=false){
 const child=cp.spawn('/bin/sh',[ROOT+'/app/start'],{detached:true,stdio:'ignore',env:{...process.env,OMARCHY_REMOTE_ROOT:ROOT}});child.unref();
 return waitReady(version,legacy);
}
async function install(bundleDir){
 if(+process.versions.node.split('.')[0]<16)throw Error('iPhone Remote requires Node 16 or newer');
 if(!fs.existsSync(path.dirname(HOOK)))throw Error('Homebrew Channel startup support is unavailable');
 const manifest=read(bundleDir+'/manifest.json');
 if(!manifest||!/^\d+\.\d+\.\d+$/.test(manifest.version)||!/^[a-f0-9]{64}$/.test(manifest.sha256))throw Error('Invalid remote bundle');
 const archive=bundleDir+'/bundle.tar.gz';
 if(crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex')!==manifest.sha256)throw Error('Damaged remote bundle');
 const old=read(ROOT+'/install.json',null);
 if(old&&old.sha256===manifest.sha256){try{return await waitReady(manifest.version);}catch(e){await stop();return start(manifest.version);}}
 const dest=ROOT+'/releases/'+manifest.sha256;
 fs.mkdirSync(ROOT+'/releases',{recursive:true,mode:0o700});fs.chmodSync(ROOT,0o700);
 if(!fs.existsSync(dest)){const temp=dest+'.partial';fs.rmSync(temp,{recursive:true,force:true});fs.mkdirSync(temp,{mode:0o700});try{cp.execFileSync('tar',['-xzf',archive,'-C',temp],{timeout:30000});cp.execFileSync(process.execPath,['--check',temp+'/server.js']);fs.renameSync(temp,dest);}catch(e){fs.rmSync(temp,{recursive:true,force:true});throw e;}}
 if(!fs.existsSync(ROOT+'/control-token'))fs.writeFileSync(ROOT+'/control-token',crypto.randomBytes(32).toString('hex'),{mode:0o600});
 let previous=null;try{const st=fs.lstatSync(ROOT+'/app');previous=st.isSymbolicLink()?fs.readlinkSync(ROOT+'/app'):ROOT+'/releases/legacy';}catch(e){if(e.code!=='ENOENT')throw e;}
 await stop();
 if(previous){const st=fs.lstatSync(ROOT+'/app');if(st.isDirectory())fs.renameSync(ROOT+'/app',previous);else fs.unlinkSync(ROOT+'/app');}
 fs.symlinkSync(dest,ROOT+'/app');
 const homeId=read(path.resolve(bundleDir,'../../appinfo.json'),{}).id||old&&old.homeId||'com.omarchytv.home';
 try{
   atomic(ROOT+'/install.json',{...manifest,homeId,source:path.resolve(bundleDir)});
   const status=await start(manifest.version);
   const environment='OMARCHY_REMOTE_ROOT='+shellQuote(ROOT)+' OMARCHY_REMOTE_PORT='+Number(process.env.OMARCHY_REMOTE_PORT||18767)+' OMARCHY_REMOTE_HAP_PORT='+Number(process.env.OMARCHY_REMOTE_HAP_PORT||51826);
   fs.writeFileSync(HOOK+'.new','#!/bin/sh\n[ -x '+shellQuote(ROOT+'/app/start')+' ] || exit 0\n'+environment+' nohup '+shellQuote(ROOT+'/app/start')+' >/dev/null 2>&1 </dev/null &\nexit 0\n',{mode:0o755});fs.renameSync(HOOK+'.new',HOOK);
   return status;
 }catch(e){await stop();fs.unlinkSync(ROOT+'/app');if(previous)fs.symlinkSync(previous,ROOT+'/app');if(old)atomic(ROOT+'/install.json',old);else fs.rmSync(ROOT+'/install.json',{force:true});if(previous)await start(old&&old.version,true).catch(()=>{});throw e;}
}
async function run(action,bundleDir=__dirname){
 if(!['status','sync','enable','disable','reset','remove'].includes(action))throw Error('Unknown operation');
 if(action==='status'){
   if(!fs.existsSync(ROOT+'/app/server.js'))return {installed:false,running:false,enabled:false,paired:false};
   try{return {installed:true,...await request('status')};}catch(e){return {installed:true,running:false,enabled:!!read(ROOT+'/config.json',{}).enabled,lastError:'Remote is stopped'};}
 }
 if(action==='remove'){
   if(fs.existsSync(ROOT+'/app/server.js')){await request('disable').catch(()=>{});await stop();}
   const cfg=read(ROOT+'/config.json',null);if(cfg){cfg.enabled=false;atomic(ROOT+'/config.json',cfg);}
   if(fs.existsSync(HOOK)&&fs.readFileSync(HOOK,'utf8').includes(ROOT+'/'))fs.unlinkSync(HOOK);
   fs.rmSync(ROOT+'/app',{recursive:true,force:true});fs.rmSync(ROOT+'/releases',{recursive:true,force:true});fs.rmSync(ROOT+'/install.json',{force:true});
   return {installed:false,enabled:false,running:false,paired:false};
 }
 if(action==='disable'){
   // Turning off installed code must never depend on a replacement bundle.
   try{return await request('disable');}catch(e){
     await stop();const cfg=read(ROOT+'/config.json',null);
     if(cfg){cfg.enabled=false;atomic(ROOT+'/config.json',cfg);}
     return run('status');
   }
 }
 if(action==='reset')return request('reset');
 if(action==='sync'&&!fs.existsSync(ROOT+'/app/server.js'))return {installed:false};
 const status=await install(bundleDir);
 if(action==='sync')return status;
 return request(action);
}
if(require.main===module){
  if(process.argv[2]==='--locked')run(process.argv[3],process.argv[4]).then(r=>console.log(JSON.stringify(r)),e=>{console.log(JSON.stringify({error:e.message}));process.exitCode=1;});
  else {
    try {
      fs.mkdirSync(ROOT,{recursive:true,mode:0o700});
      const r=cp.spawnSync('flock',['-w','45',ROOT+'/install.lock',process.execPath,__filename,'--locked',process.argv[2]||'status',process.argv[3]||__dirname],{encoding:'utf8',timeout:180000,maxBuffer:262144});
      if(r.error||!r.stdout)throw Error('Remote setup is busy or flock is unavailable');
      process.stdout.write(r.stdout);process.exitCode=r.status||0;
    }catch(e){console.log(JSON.stringify({error:e.message}));process.exitCode=1;}
  }
}
module.exports={run,install,read,atomic,readiness};
