'use strict';
const cp = require('child_process');
function luna(uri, payload = {}) {
  return new Promise((resolve, reject) => cp.execFile('/usr/bin/luna-send', ['-n','1','-w','2500','luna://'+uri,JSON.stringify(payload)], {timeout:3500,maxBuffer:1048576}, (err, out) => {
    if (err) return reject(Error('TV service unavailable'));
    try { const value=JSON.parse(out); if(value.returnValue!==true) throw Error(value.errorText||'TV refused command'); resolve(value); } catch(e) { reject(e); }
  }));
}
const keyCodes={up:103,down:108,left:105,right:106,select:28,back:412,settings:139,volumeUp:115,volumeDown:114,rewind:168,fastForward:208,next:163,previous:165,play:207,pause:119};
function createPlatform(call=luna,homeId='com.omarchytv.home') {
  let pending=Promise.resolve(),queued=0;
  function key(name) {
    if(!Object.hasOwnProperty.call(keyCodes,name)) return Promise.reject(Error('Unknown key'));
    if(queued>=16) return Promise.reject(Error('Remote busy'));
    queued++;
    const result=pending.then(()=>call('com.webos.service.networkinput/test/sendKeyCode',{keyCode:keyCodes[name]}));
    pending=result.catch(()=>{}).finally(()=>queued--); return result;
  }
  const launch=(id,params)=>call('com.webos.applicationManager/launch',{id,params:params||(id===homeId?{home:true}:{})});
  async function remote(value) {
    const names={0:'rewind',1:'fastForward',2:'next',3:'previous',4:'up',5:'down',6:'left',7:'right',8:'select',9:'back',15:'settings'};
    if(value===10) return launch(homeId);
    if(value===11) {
      const r=await call('com.webos.media/getForegroundAppInfo',{}).catch(()=>({}));
      const media=(r.foregroundAppInfo||[]).find(a=>a.playState);
      return key(media&&media.playState==='playing'&&media.type!=='external input'?'pause':'play');
    }
    if(!names[value]) throw Error('Unsupported remote command');
    return key(names[value]);
  }
  return {key,remote,launch,call};
}
module.exports={createPlatform,luna};
