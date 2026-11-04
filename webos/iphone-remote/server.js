'use strict';
const os=require('os');
const fs=require('fs'),path=require('path'),crypto=require('crypto'),http=require('http');
const {createPlatform}=require('./platform');
function createRemoteServer(options={}) {
const hap=options.hap||require('hap-nodejs'),QR=options.qr||require('qrcode');
const ROOT=options.root||process.env.OMARCHY_REMOTE_ROOT||'/var/lib/omarchytv-remote';
const port=options.port===undefined?Number(process.env.OMARCHY_REMOTE_PORT||18767):options.port;
const hapPort=options.hapPort===undefined?Number(process.env.OMARCHY_REMOTE_HAP_PORT||51826):options.hapPort;
const startupTimeout=options.startupTimeoutMs||10000;
function timed(promise,ms,label){let timer;return Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(label+' timed out')),ms);})]).finally(()=>clearTimeout(timer));}
const token=fs.readFileSync(ROOT+'/control-token','utf8').trim();
const installed=JSON.parse(fs.readFileSync(ROOT+'/install.json','utf8'));
const version=require('./package.json').version;
fs.mkdirSync(ROOT+'/persist',{recursive:true,mode:0o700});
hap.HAPStorage.setCustomStoragePath(ROOT+'/persist');
function save(value){const tmp=ROOT+'/config.json.new';fs.writeFileSync(tmp,JSON.stringify(value),{mode:0o600});fs.renameSync(tmp,ROOT+'/config.json');}
function newPin(){let pin;do{pin=String(crypto.randomInt(10000000,99999999));}while(/^(\d)\1+$/.test(pin)||pin==='12345678'||pin==='87654321');return pin.slice(0,3)+'-'+pin.slice(3,5)+'-'+pin.slice(5);}
let cfg;
try{cfg=JSON.parse(fs.readFileSync(ROOT+'/config.json'));}catch(e){if(e.code!=='ENOENT')throw e;cfg={enabled:false,username:'02:'+Array.from(crypto.randomBytes(5),b=>b.toString(16).padStart(2,'0')).join(':'),pincode:newPin(),inputIds:{}};save(cfg);}
const {Accessory,Service,Characteristic:C,Categories,uuid}=hap;
// Optional root-owned integration adapter lives outside versioned code.
const localAdapter=ROOT+'/local-platform.js';
const p=options.platform||(fs.existsSync(localAdapter)?require(localAdapter).createPlatform():createPlatform(undefined,installed.homeId));
const localActions=fs.existsSync(ROOT+'/local-actions.json')?JSON.parse(fs.readFileSync(ROOT+'/local-actions.json','utf8')):{};
let accessory,tv,speaker,published=false,qr='',lastError='',lastCommand=null,inputs=[],refreshing=false;
let initialization='starting',startupError='',queue=Promise.resolve(),pending=0,closing=false;
function command(fn){return async value=>{try{await fn(value);lastCommand=Date.now();lastError='';}catch(e){lastError=e.message;throw new hap.HapStatusError(hap.HAPStatus.SERVICE_COMMUNICATION_FAILURE);}};}
async function refresh(){
  if(!published||refreshing)return;refreshing=true;const current=accessory;
  try{
    const values=await Promise.allSettled([p.call('com.webos.service.tvpower/power/getPowerState'),p.call('com.webos.applicationManager/getForegroundAppInfo'),p.call('com.webos.audio/getVolume')]);
    if(accessory!==current||!published)return;
    if(values[0].status==='fulfilled')tv.updateCharacteristic(C.Active,values[0].value.state==='Active'?1:0);
    if(values[1].status==='fulfilled'){const input=inputs.find(i=>i.appId===values[1].value.appId);if(input)tv.updateCharacteristic(C.ActiveIdentifier,input.identifier);}
    if(values[2].status==='fulfilled'){const v=values[2].value;speaker.updateCharacteristic(C.Mute,!!v.muteStatus);if(typeof v.volume==='number')speaker.updateCharacteristic(C.Volume,v.volume);}
  }finally{refreshing=false;}
}
async function start(){
  if(published)return;
  if(accessory)await stop();
  const catalog=await timed(p.call('com.webos.applicationManager/listLaunchPoints'),startupTimeout,'TV catalogue');
  const sources=await timed(p.call('com.webos.service.eim/getAllInputStatus'),startupTimeout,'TV inputs').catch(()=>({devices:[]}));
  const list=[{appId:installed.homeId,name:'OmarchyTV Home'},...(localActions.inputs||[]),...(sources.devices||[]).map(a=>({appId:a.appId,name:a.label||a.id})),...(catalog.launchPoints||[]).filter(a=>!a.hidden&&a.id!=='com.omarchytv.home'&&a.id!=='com.webos.app.home').map(a=>({appId:a.id,name:a.title||a.id}))];
  inputs=list.filter((v,i,a)=>v.appId&&a.findIndex(x=>x.appId===v.appId)===i).slice(0,40);
  let next=Math.max(0,...Object.values(cfg.inputIds))+1;
  inputs.forEach(i=>{if(!cfg.inputIds[i.appId])cfg.inputIds[i.appId]=next++;i.identifier=cfg.inputIds[i.appId];});save(cfg);
  accessory=new Accessory('OmarchyTV',uuid.generate('omarchytv-remote:'+cfg.username));
  accessory.category=Categories.TELEVISION;
  accessory.getService(Service.AccessoryInformation).setCharacteristic(C.Manufacturer,'OmarchyTV').setCharacteristic(C.Model,'webOS Remote').setCharacteristic(C.SerialNumber,cfg.username.replace(/:/g,'')).setCharacteristic(C.FirmwareRevision,version);
  tv=accessory.addService(Service.Television,'OmarchyTV','tv');tv.setPrimaryService();
  tv.setCharacteristic(C.ConfiguredName,'OmarchyTV').setCharacteristic(C.SleepDiscoveryMode,C.SleepDiscoveryMode.ALWAYS_DISCOVERABLE);
  tv.getCharacteristic(C.Active).onSet(command(v=>p.call('com.webos.service.tvpower/power/'+(v?'powerOn':'powerOff'),{reason:'remoteKey'})));
  tv.getCharacteristic(C.RemoteKey).onSet(command(v=>p.remote(v)));
  tv.getCharacteristic(C.ActiveIdentifier).onSet(command(v=>{const input=inputs.find(i=>i.identifier===v);if(!input)throw Error('Input unavailable');return p.launch(input.targetAppId||input.appId,input.params);}));
  tv.getCharacteristic(C.PowerModeSelection).onSet(command(()=>localActions.settings?p.launch(localActions.settings.appId,localActions.settings.params):p.key('settings')));
  speaker=accessory.addService(Service.TelevisionSpeaker,'TV sound','speaker');
  speaker.setCharacteristic(C.Active,1).setCharacteristic(C.VolumeControlType,C.VolumeControlType.RELATIVE);
  speaker.getCharacteristic(C.VolumeSelector).onSet(command(v=>p.key(v===0?'volumeUp':'volumeDown')));
  speaker.getCharacteristic(C.Mute).onSet(command(v=>p.call('com.webos.audio/setMuted',{muted:!!v})));
  speaker.getCharacteristic(C.Volume).onGet(async()=>{const v=await p.call('com.webos.audio/getVolume');return v.volume;});
  tv.addLinkedService(speaker);
  inputs.forEach(i=>{const name=String(i.name).replace(/[^\p{L}\p{N} .'-]/gu,'').trim().slice(0,64)||'TV Input';const s=accessory.addService(Service.InputSource,name,i.appId);s.setCharacteristic(C.Identifier,i.identifier).setCharacteristic(C.ConfiguredName,name).setCharacteristic(C.IsConfigured,1).setCharacteristic(C.InputSourceType,/hdmi/.test(i.appId)?3:10);tv.addLinkedService(s);});
  accessory.on('identify',(_paired,cb)=>{p.call('com.webos.notification/createToast',{message:'This is your OmarchyTV iPhone Remote'}).then(()=>cb(),()=>cb());});
  const current=accessory;
  // publish() only starts listening. HomeKit is ready after mDNS advertisement.
  let timer,advertised,onError;
  const ready=new Promise((resolve,reject)=>{
    advertised=resolve;onError=reject;
    current.once('advertised',advertised);current.once('error',onError);
    timer=setTimeout(()=>reject(Error('HomeKit advertisement timed out')),startupTimeout);
  });
  try {
    await Promise.all([ready,current.publish({username:cfg.username,pincode:cfg.pincode,category:Categories.TELEVISION,port:hapPort,advertiser:'ciao'})]);
  } finally {
    clearTimeout(timer);current.removeListener('advertised',advertised);current.removeListener('error',onError);
  }
  current.on('error',e=>{if(accessory!==current||closing)return;published=false;initialization='failed';startupError=e.message||'HomeKit failed';});
  qr=await timed(QR.toDataURL(current.setupURI(),{width:320,margin:3,errorCorrectionLevel:'M'}),startupTimeout,'Pairing QR code');
  published=true;
  refresh().catch(()=>{});
}
async function stop(){
  published=false;qr='';
  const current=accessory;
  if(current)await timed(current.unpublish(),startupTimeout,'HomeKit shutdown');
  accessory=null;
}
function transition(action){
  if(pending>=8)return Promise.reject(Error('Setup is busy'));
  pending++;
  const task=queue.then(async()=>{
    if(closing&&action!=='shutdown')throw Error('Service is shutting down');
    initialization='starting';startupError='';
    try {
      if(action==='disable'||action==='shutdown'){
        await stop();
        if(action==='disable'){cfg.enabled=false;save(cfg);}
        initialization='disabled';
      }else{
        if(action==='reconnect')await stop();
        if(action==='reset'){
          await stop();Accessory.cleanupAccessoryData(cfg.username);cfg.pincode=newPin();save(cfg);
        }
        if(action==='enable'){cfg.enabled=true;save(cfg);}
        if(cfg.enabled){await start();initialization='ready';}
        else initialization='disabled';
      }
      return status();
    }catch(e){
      startupError=e.message;initialization='failed';
      await stop().catch(cleanup=>{startupError+='; '+cleanup.message;});
      throw e;
    }
  });
  queue=task.catch(()=>{}).finally(()=>{pending--;});
  return task;
}
function status(){return {installed:true,version,initialization,ready:initialization==='ready'||initialization==='disabled',startupError,enabled:cfg.enabled,running:published,paired:!!(accessory&&accessory._accessoryInfo&&accessory._accessoryInfo.paired()),name:'OmarchyTV',qr:accessory&&accessory._accessoryInfo&&accessory._accessoryInfo.paired()?'':qr,pincode:cfg.enabled&&!(accessory&&accessory._accessoryInfo&&accessory._accessoryInfo.paired())?cfg.pincode:null,lastError,lastCommand,inputCount:inputs.length};}
function respond(res,code,value){res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store','Access-Control-Allow-Origin':'null'});res.end(JSON.stringify(value));}
const server=http.createServer(async(req,res)=>{
  if(req.headers.host!=='127.0.0.1:'+(server.address()?server.address().port:port))return respond(res,403,{error:'Invalid host'});
  if(req.method==='OPTIONS'){res.writeHead(204,{'Access-Control-Allow-Origin':'null','Access-Control-Allow-Headers':'X-Omarchy-Token, Content-Type','Access-Control-Allow-Methods':'GET, POST, OPTIONS'});return res.end();}
  if(req.headers['x-omarchy-token']!==token)return respond(res,403,{error:'Unauthorized'});
  if(req.method==='GET'&&req.url==='/status')return respond(res,200,status());
  if(req.method!=='POST'||!['/enable','/disable','/reset'].includes(req.url))return respond(res,404,{error:'Unknown operation'});
  req.resume();
  if(closing)return respond(res,503,{error:'Service is shutting down'});
  try{respond(res,200,await transition(req.url.slice(1)));}
  catch(e){respond(res,503,{error:e.message});}
});
const boot=transition('boot');boot.catch(e=>console.error('Remote startup failed:',e.message));
server.listen(port,'127.0.0.1');
// Suspend pauses JS timers; network changes can invalidate the mDNS advertisement.
const now=options.now||Date.now;
const networkFingerprint=options.networkFingerprint||(()=>JSON.stringify(Object.entries(os.networkInterfaces()).sort().map(([name,items])=>[name,items.filter(i=>!i.internal).map(i=>i.address).sort()])));
let lastTick=now(),network=networkFingerprint(),rediscover=false,nextRecovery=0,recoveryDelay=1000;
async function maintenance(){
  const time=now(),nextNetwork=networkFingerprint();
  if(time-lastTick>15000||nextNetwork!==network)rediscover=true;
  lastTick=time;network=nextNetwork;
  if(closing||pending||!cfg.enabled)return;
  if((rediscover||initialization==='failed')&&time>=nextRecovery){
    rediscover=false;
    try{await transition('reconnect');recoveryDelay=1000;nextRecovery=0;}
    catch(e){nextRecovery=now()+recoveryDelay;recoveryDelay=Math.min(recoveryDelay*2,30000);}
    return;
  }
  await refresh();
}
const refreshTimer=setInterval(()=>maintenance().catch(()=>{}),5000);refreshTimer.unref();
async function close(){
  closing=true;clearInterval(refreshTimer);
  await transition('shutdown');
  await new Promise(resolve=>server.close(resolve));
}
return {server,status,transition,boot,close,maintenance};
}
if(require.main===module){
  const app=createRemoteServer();
  for(const sig of ['SIGTERM','SIGINT'])process.on(sig,()=>{app.close().then(()=>process.exit(0),()=>process.exit(1));});
}
module.exports={createRemoteServer};
