'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm');
const source=fs.readFileSync(require.resolve('../app/iphone-remote.js'),'utf8');
function harness(platform){
 const calls=[],nodes={};function element(){return {textContent:'',style:{},children:[],appendChild(e){this.children.push(e);}};}
 let status={installed:false,enabled:false,running:false,paired:false};
 const document={hidden:false,getElementById(id){return nodes[id]||(nodes[id]=element());},createElement:element};
 const h={state:{modalOpen:false},modal(title,sub,rows){this.state.modalOpen=true;this.rows=rows;document.getElementById('modal-title').textContent=title;document.getElementById('modal-sub').textContent=sub;},close(){h.state.modalOpen=false;}};
 function Bridge(){this.cancel=()=>{};this.call=(uri,payload)=>{const c=JSON.parse(payload).command;calls.push(c);const action=c.match(/manage\.js' '([^']+)'/)[1];queueMicrotask(()=>{if(action==='enable')status={installed:true,enabled:true,running:true,paired:false,qr:'data:image/png;base64,test',pincode:'000-00-000'};if(action==='disable')status.enabled=false;this.onservicecallback(JSON.stringify({returnValue:true,stdoutString:JSON.stringify(status)}));});};}
 const context={window:null,PalmServiceBridge:Bridge,OMARCHY_PLATFORM:platform,document,location:{pathname:'/media/developer/apps/usr/palm/applications/com.omarchytv.home/index.html'},Promise,Error,JSON,decodeURIComponent,setTimeout:()=>1,clearTimeout:()=>{}};context.window=context;vm.runInNewContext(source,context);context.OmarchyRemote.attach(h);
 return {api:context.OmarchyRemote,h,calls,document,setStatus:s=>status=s};
}
const tick=()=>new Promise(r=>setImmediate(r));
test('opening settings does not enable remote; reset and removal require confirmation',async()=>{
 const x=harness();await tick();x.api.open();await tick();assert.ok(x.h.rows.some(r=>r.label==='Enable iPhone Remote'));assert.ok(x.calls.every(c=>!c.includes("'enable'")));
 x.h.rows.find(r=>r.label==='Enable iPhone Remote').act();await tick();assert.ok(x.document.getElementById('modal-sub').children.length);
 x.h.rows.find(r=>r.label==='Reset pairing…').act();await tick();assert.ok(!x.calls.some(c=>c.includes("'reset'")));assert.equal(x.document.getElementById('modal-title').textContent,'Reset pairing');
 x.h.rows.find(r=>r.label==='Cancel').act();await tick();x.h.rows.find(r=>r.label==='Remove remote service…').act();assert.ok(!x.calls.some(c=>c.includes("'remove'")));
});
test('unsupported platforms never invoke Homebrew Channel',async()=>{const x=harness('tizen');await tick();assert.equal(x.api.supported(),false);assert.equal(x.calls.length,0);});
test('late status cannot reopen a closed menu',async()=>{const x=harness();await tick();x.api.open();x.h.rows[0].act();await tick();assert.equal(x.document.getElementById('modal-title').textContent,'Remotes');});
test('system Home can provide a narrow management transport',async()=>{
 const x=harness();await tick();const calls=[];
 x.api.attach({state:x.h.state,modal:x.h.modal.bind(x.h),close:x.h.close,remoteRequest:action=>{calls.push(action);return Promise.resolve({installed:true,enabled:true,running:true,paired:true});}});
 x.api.open();await tick();assert.ok(calls.includes('status'));assert.match(x.document.getElementById('modal-sub').textContent,/Control Center/);
});
