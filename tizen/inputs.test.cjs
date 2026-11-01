const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function boot(info, options = {}) {
  const handlers = {}, events = [], sets = [], launches = [], hides = [];
  let current = null, sourceCalls = 0;
  const ctx = {setTimeout:options.setTimeout || setTimeout, clearTimeout:options.clearTimeout || clearTimeout, Date:options.Date || Date, Promise, Number, Error,
    document:{hidden:false,title:'TV',addEventListener:(k,f)=>handlers[k]=f},
    CustomEvent:function(type,init){this.type=type;this.detail=init.detail;},dispatchEvent:e=>events.push(e),
    tizen:{application:{getCurrentApplication:()=>({hide(){if(options.hideError)throw options.hideError;hides.push(1);}}),getAppsInfo:ok=>ok([]),getAppsContext:ok=>ok([{appId:'OmKeyCtrl0.Keys'}]),launch:(id,ok,fail)=>{launches.push(id);options.launchError?fail(options.launchError):ok();}},tvinputdevice:{registerKey(){}},
      systeminfo:{getCapability:k=>{if(options.capError)throw options.capError;return options.platform||'6.0';},getPropertyValue:(key,ok,fail)=>{assert.equal(key,'VIDEOSOURCE');sourceCalls++;if(options.sourceHook)options.sourceHook(ok,fail,sourceCalls);else options.readError?fail(options.readError):ok(info);}},
      tvwindow:{setSource:(s,ok,fail,type)=>{sets.push(s);assert.equal(type,'MAIN');current=s;if(options.setHook)options.setHook(ok,fail);else options.setError?fail(options.setError):ok();},getSource:()=>options.readback || (options.mismatch?{type:'HDMI',number:99}:current)}}};
  ctx.window=ctx;vm.runInNewContext(fs.readFileSync(__dirname+'/tizen-shim.js','utf8'),ctx);
  return {ctx,handlers,events,sets,launches,hides,sourceCalls:()=>sourceCalls};
}
function select(b,id){return new Promise((ok,no)=>b.ctx.OMARCHY_TIZEN_SWITCH_INPUT(id,ok,no));}
const hdmi1={type:'HDMI',number:1,signal:false,editName:'Console'};
const hdmi2={type:'HDMI',number:2,signal:true,editName:''};
test('discover actual HDMI ports, sort and refresh after Home, with connection labels',()=>{
  const info={connected:[{type:'TV',number:1},hdmi2],disconnected:[hdmi1]},b=boot(info);
  assert.deepEqual(Array.from(b.ctx.OMARCHY_TIZEN_INPUTS,x=>x.id),['tv','hdmi1','hdmi2']);
  assert.match(b.ctx.OMARCHY_TIZEN_INPUTS[1].sub,/No signal/);
  assert.match(b.ctx.OMARCHY_TIZEN_INPUTS[2].sub,/Connected/);
  assert.ok(b.events.some(e=>e.type==='omarchy-tizen-inputs'));
  info.disconnected.push({type:'HDMI',number:3,signal:false,editName:''});b.handlers.visibilitychange();
  assert.equal(b.ctx.OMARCHY_TIZEN_INPUTS.length,4);
});
test('select with a fresh untouched native source and launch viewer only after matching readback',async()=>{
  const info={connected:[hdmi2],disconnected:[hdmi1]},b=boot(info);
  await select(b,'hdmi1');
  assert.equal(b.sourceCalls(),2,'selection must re-read source catalogue');
  assert.equal(b.sets[0],hdmi1,'Samsung rejects reconstructed native source objects');
  assert.deepEqual(b.launches,['org.tizen.tv-viewer']);
});
test('reject unavailable ports and invalid ids without changing input',async()=>{
  const b=boot({connected:[hdmi2],disconnected:[]});
  await assert.rejects(select(b,'hdmi1'),/no longer available/i);
  await assert.rejects(select(b,'hdmi4;echo bad'),/valid|available/i);
  assert.equal(b.sets.length,0);assert.equal(b.launches.length,0);
});
test('do not open viewer after setSource failure or mismatched readback',async()=>{
  for(const options of [{setError:{message:'denied'}},{mismatch:true}]) {
    const b=boot({connected:[hdmi2],disconnected:[]},options);
    await assert.rejects(select(b,'hdmi2'),e=>/denied|could not confirm/i.test(e.message));
    assert.equal(b.launches.length,0);
  }
});
test('report viewer launch failure after the source has changed',async()=>{
  const b=boot({connected:[hdmi2],disconnected:[]},{launchError:{message:'viewer denied'}});
  await assert.rejects(select(b,'hdmi2'),/switched.*viewer/i);
  assert.equal(b.sets.length,1);
});
test('TV selects the fresh native tuner source after HDMI before opening the viewer',async()=>{
  const tv={type:'TV',number:0,signal:null,editName:null};
  const info={connected:[hdmi2,tv],disconnected:[]},b=boot(info);
  await select(b,'hdmi2');await select(b,'tv');
  assert.deepEqual(b.sets,[hdmi2,tv]);
  assert.equal(b.sets[1],tv,'preserve the native TV source object');
  assert.equal(b.sourceCalls(),3,'refresh sources before each selection');
  assert.deepEqual(b.launches,['org.tizen.tv-viewer','org.tizen.tv-viewer']);
});
test('do not invent a tuner source when the TV does not report one',async()=>{
  const b=boot({connected:[hdmi2],disconnected:[]});
  assert.deepEqual(Array.from(b.ctx.OMARCHY_TIZEN_INPUTS,x=>x.id),['hdmi2']);
  await assert.rejects(select(b,'tv'),e=>/no longer available/i.test(e.message));
  assert.equal(b.sets.length,0);assert.equal(b.launches.length,0);
});
test('do not open the viewer after a TV source failure or mismatched source type',async()=>{
  for(const options of [{setError:{message:'tuner denied'}},{readback:{type:'HDMI',number:1}}]){
    const b=boot({connected:[{type:'TV',number:1}],disconnected:[]},options);
    await assert.rejects(select(b,'tv'),e=>/denied|could not confirm/i.test(e.message));
    assert.equal(b.sets.length,1);assert.equal(b.launches.length,0);
  }
});
test('discovery keeps installed artwork paths for known and future apps',()=>{
  const rows=[{id:'org.tizen.netflix-app',name:'Netflix',iconPath:'/opt/share/icons/netflix.png'},{id:'AbCdEfGhIj.Future',name:'Future',iconPath:'/opt/usr/apps/future/icon.png'}];
  const b=boot({connected:[],disconnected:[]});
  b.ctx.tizen.application.getAppsInfo=ok=>ok(rows);b.handlers.visibilitychange();
  assert.deepEqual(Array.from(b.ctx.OMARCHY_TIZEN_APPS,x=>x.iconPath),['/opt/share/webappservice/apps_icon/all/3201907018807/304x304.png',rows[1].iconPath]);
});

test('late source callbacks cannot change input after timeout and concurrent selection is refused',async()=>{
  let pending, expire, now=0;
  const info={connected:[hdmi2],disconnected:[]};
  const b=boot(info,{Date:{now:()=>now},setTimeout:f=>{expire=f;return 1;},clearTimeout(){},sourceHook:(ok,fail,count)=>{if(count===1)ok(info);else pending=ok;}});
  const request=select(b,'hdmi2');
  await assert.rejects(select(b,'hdmi2'),e=>/in progress/.test(e.message));
  now=8001;expire();await assert.rejects(request,e=>/did not confirm/.test(e.message));
  pending(info);assert.equal(b.sets.length,0);assert.equal(b.launches.length,0);
});
test('a source catalogue arriving without enough follow-on time cannot start a source mutation',async()=>{
  let now=0;
  const info={connected:[hdmi2],disconnected:[]};
  const b=boot(info,{Date:{now:()=>now},sourceHook:(ok,fail,count)=>{if(count>1)now=6001;ok(info);}});
  await assert.rejects(select(b,'hdmi2'),e=>/Not enough time/.test(e.message));
  assert.equal(b.sets.length,0);assert.equal(b.launches.length,0);
});

test('a late native source-change callback cannot launch the viewer after timeout',async()=>{
  let pending,expire,now=0;
  const b=boot({connected:[hdmi2],disconnected:[]},{Date:{now:()=>now},setTimeout:f=>{expire=f;return 1;},clearTimeout(){},setHook:ok=>{pending=ok;}});
  const request=select(b,'hdmi2');assert.equal(b.sets.length,1);
  now=8001;expire();await assert.rejects(request,e=>/did not confirm/.test(e.message));
  pending();assert.equal(b.launches.length,0);
});

test('connection labels use the TV catalogue even when Samsung returns a null signal',()=>{
  const b=boot({connected:[{type:'HDMI',number:1,signal:null}],disconnected:[]});
  assert.match(b.ctx.OMARCHY_TIZEN_INPUTS[0].sub,/Connected/);
});
test('Prime and Internet use verified readable store artwork when their native icon files are unusable',()=>{
  const rows=[{id:'org.tizen.primevideo',name:'Prime Video',iconPath:'/opt/usr/apps/org.tizen.primevideo/shared/res/ignition.png'},{id:'org.tizen.browser',name:'Internet',iconPath:'/opt/usr/apps/org.tizen.browser/shared/res/org.tizen.browser.png'}];
  const b=boot({connected:[],disconnected:[]});b.ctx.tizen.application.getAppsInfo=ok=>ok(rows);b.handlers.visibilitychange();
  assert.deepEqual(Array.from(b.ctx.OMARCHY_TIZEN_APPS,x=>x.iconPath),['/opt/share/webappservice/apps_icon/all/3201910019365/304x304.png','/opt/share/webappservice/apps_icon/all/3202010022079/304x304.png']);
});

test('YouTube uses the verified branded store artwork instead of the generic Tizen icon',()=>{
  const b=boot({connected:[],disconnected:[]});b.ctx.tizen.application.getAppsInfo=ok=>ok([{id:'9Ur5IzDKqV.TizenYouTube',name:'YouTube',iconPath:'/opt/usr/apps/9Ur5IzDKqV/shared/res/9Ur5IzDKqV.TizenYouTube.png'}]);b.handlers.visibilitychange();
  assert.equal(b.ctx.OMARCHY_TIZEN_APPS[0].iconPath,'/opt/share/webappservice/apps_icon/all/111299001912/304x304.png');
});

test('artwork candidates retain the native icon after the store cache and deduplicate paths',()=>{
  const cache='/opt/share/webappservice/apps_icon/all/3201907018807/304x304.png';
  const rows=[{id:'org.tizen.netflix-app',name:'Netflix',iconPath:'/native/netflix.png'},{id:'org.tizen.primevideo',name:'Prime Video',iconPath:'/opt/share/webappservice/apps_icon/all/3201910019365/304x304.png'},{id:'AbCdEfGhIj.Future',name:'Future',iconPath:'/native/future.png'}];
  const b=boot({connected:[],disconnected:[]});b.ctx.tizen.application.getAppsInfo=ok=>ok(rows);b.handlers.visibilitychange();
  const apps=b.ctx.OMARCHY_TIZEN_APPS;
  assert.deepEqual(Array.from(apps[0].iconPaths),[cache,'/native/netflix.png']);
  assert.deepEqual(Array.from(apps[1].iconPaths),[rows[1].iconPath]);
  assert.deepEqual(Array.from(apps[2].iconPaths),['/native/future.png']);
  assert.equal(apps[0].iconPath,cache,'legacy single-path consumers keep the preferred candidate');
});

test('Tizen 5.5: HDMI is shown by hiding OmarchyTV, without launching the tv-viewer (#87)',async()=>{
  const b=boot({connected:[hdmi2],disconnected:[]},{platform:'5.5'});
  await select(b,'hdmi2');
  assert.deepEqual(b.sets,[hdmi2]);assert.equal(b.launches.length,0);assert.equal(b.hides.length,1);
});
test('Tizen 5.5: TV tuner still opens the viewer; hide failure falls back to the viewer',async()=>{
  const tv={type:'TV',number:0,signal:null,editName:null};
  const b=boot({connected:[hdmi2,tv],disconnected:[]},{platform:'5.5'});
  await select(b,'tv');assert.deepEqual(b.launches,['org.tizen.tv-viewer']);assert.equal(b.hides.length,0);
  const c=boot({connected:[hdmi2],disconnected:[]},{platform:'5.5',hideError:new Error('no')});
  await select(c,'hdmi2');assert.deepEqual(c.launches,['org.tizen.tv-viewer']);
});
test('Tizen 6+ and unknown versions keep the viewer hand-off for HDMI',async()=>{
  for(const options of [{platform:'6.5'},{capError:new Error('x')}]){
    const b=boot({connected:[hdmi2],disconnected:[]},options);
    await select(b,'hdmi2');assert.deepEqual(b.launches,['org.tizen.tv-viewer']);assert.equal(b.hides.length,0);
  }
});
