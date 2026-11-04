/* Optional rooted-webOS remote. No PC, LG AirPlay or custom Home helper required. */
(function(root){
  'use strict';
  var h,epoch=0,timer,bridges=[],checking=false,last=null,updateError=null,visibilityBound=false;
  function supported(){return !!root.PalmServiceBridge&&(!root.OMARCHY_PLATFORM||root.OMARCHY_PLATFORM==='webos');}
  function quote(s){return "'"+s.replace(/'/g,"'\\''")+"'";}
  function execute(action){if(h&&h.remoteRequest)return h.remoteRequest(action);return new Promise(function(resolve,reject){
    if(!supported())return reject(Error('Requires a rooted LG webOS TV with Homebrew Channel.'));
    var dir=decodeURIComponent(location.pathname.replace(/\/[^\/]*$/,''))+'/rooted/iphone-remote';
    var bundled=quote(dir+'/manage.js'),installed=quote('/var/lib/omarchytv-remote/app/manage.js');
    // Regular-IPK upgrades may remove the bundled backend. Keep installed-service recovery accessible.
    var command='if [ -f '+bundled+' ]; then /usr/bin/node '+bundled+' '+quote(action)+' '+quote(dir)+
      '; elif [ -f '+installed+' ]; then /usr/bin/node '+installed+' '+quote(action)+' '+quote(dir)+
      '; else printf '+quote('{"error":"Install the webOS build with iPhone Remote support."}')+'; fi';
    var b=new PalmServiceBridge(),done=false,t=setTimeout(function(){finish(Error('Remote setup timed out. Try again.'));},180000);bridges.push(b);
    function finish(err,r){if(done)return;done=true;clearTimeout(t);bridges.splice(bridges.indexOf(b),1);try{b.cancel();}catch(e){}err?reject(err):resolve(r);}
    b.onservicecallback=function(raw){try{
      var r=JSON.parse(raw),result;
      try{result=JSON.parse((r.stdoutString||'').trim());}catch(ignore){}
      // Homebrew reports nonzero exits as returnValue:false but retains our JSON error.
      if(result&&result.error)return finish(Error(result.error));
      if(!r.returnValue)return finish(Error('Homebrew Channel could not complete setup. Check that the TV is rooted and Node 16 or newer is installed.'));
      if(!result)throw Error('Missing response');
      finish(null,result);
    }catch(e){finish(Error('The iPhone Remote bundle is unavailable. Install the webOS build with iPhone Remote support.'));}};
    b.call('luna://org.webosbrew.hbchannel.service/exec',JSON.stringify({command:command}));
  });}
  function active(){return h.state.modalOpen&&document.getElementById('modal-title').textContent==='iPhone Remote';}
  function close(){epoch++;clearTimeout(timer);h.close();}
  function fail(e){if(active())h.modal('iPhone Remote',e.message,[{label:'Try again',act:open},{label:'Back',act:remotes}]);}
  function help(){epoch++;clearTimeout(timer);h.modal('Use Control Center Remote','On your iPhone, swipe down from the top-right corner.\nOpen Apple TV Remote → choose OmarchyTV at the top.\nThe picker in Apple Home only chooses apps and inputs.\n\nUse the touch area or directional buttons, Select, Back and Home. Volume and playback depend on the active app and sound output.\nDeep standby wake, Apple TV keyboard prompts and artwork are not provided by this connection.',[{label:'Back to iPhone Remote',act:open},{label:'Done',act:close}]);}
  function confirm(action,title,detail){epoch++;clearTimeout(timer);h.modal(title,detail,[{label:title,act:function(){perform(action);}},{label:'Cancel',act:open}]);}
  function recover(e,id){execute('status').then(function(s){if(id!==epoch||!active())return;updateError=e.message;display(s,updateError);watch(id,s);}).catch(function(){if(id===epoch)fail(e);});}
  function perform(action){var id=++epoch;updateError=null;clearTimeout(timer);h.modal('iPhone Remote','Working…',[]);execute(action).then(function(s){if(id!==epoch||!active())return;last=s;display(s);watch(id,s);}).catch(function(e){if(id===epoch)recover(e,id);});}
  function display(s,error){
    last=s;var rows=[],text;
    if(!s.installed||!s.enabled){text=s.installed?'iPhone Remote is off. Your pairing is saved.':'Use your iPhone’s built-in Apple TV Remote.\nEnable once, scan the QR code, then choose OmarchyTV in Control Center.\nNo LG agreement or account required.';rows.push({label:'Enable iPhone Remote',act:function(){perform('enable');}});}
    else if(!s.running){text=s.startupError||s.lastError||'The remote service is stopped.';rows.push({label:'Restart iPhone Remote',act:function(){perform('enable');}});}
    else{text=s.paired?'Connected. Open Control Center → Apple TV Remote → OmarchyTV.':'Scan with iPhone Camera, or Home → + → Add Accessory.\nChoose Add Anyway if Apple labels it uncertified.';}
    if(s.installed&&s.enabled)rows.push({label:'Turn off iPhone Remote',act:function(){confirm('disable','Turn off iPhone Remote','Pairing is saved. You can enable it again here.');}});
    rows.push({label:'How to open the remote',act:help});
    if(s.installed){rows.push({label:'Reset pairing…',act:function(){confirm('reset','Reset pairing','Disconnect every paired Home and generate a new code? Remove the old OmarchyTV accessory in Apple Home before pairing again.');}});rows.push({label:'Remove remote service…',act:function(){confirm('remove','Remove remote service','Stop the service and remove automatic startup. Pairing data is retained for reinstall.');}});}
    rows.push({label:'Back',act:remotes});
    if(error)text+='\n\nUpdate/setup error: '+error;
    h.modal('iPhone Remote',text,rows);
    var sub=document.getElementById('modal-sub');
    if(s.enabled&&s.running&&!s.paired&&s.qr){var box=document.createElement('div');box.style.cssText='display:flex;align-items:center;gap:24px;margin-top:18px';var img=document.createElement('img');img.src=s.qr;img.alt='Apple Home pairing QR code';img.width=220;img.height=220;img.style.cssText='border-radius:10px;flex:none';var label=document.createElement('div');label.textContent='OmarchyTV\n\nSetup code\n'+s.pincode+'\n\nAfter adding to Home:\nControl Center → Remote';box.appendChild(img);box.appendChild(label);sub.appendChild(box);}
    if(s.lastError&&s.running){var err=document.createElement('div');err.textContent='Last control error: '+s.lastError;sub.appendChild(err);}
  }
  function watch(id,prior){clearTimeout(timer);if(!prior.running||prior.paired)return;timer=setTimeout(function(){if(id!==epoch||!active()||document.hidden)return;execute('status').then(function(s){if(id!==epoch||!active())return;if(s.paired!==prior.paired||s.running!==prior.running)display(s,updateError);watch(id,s);}).catch(function(){if(id===epoch&&active())watch(id,prior);});},3000);}
  function open(){var id=++epoch;updateError=null;clearTimeout(timer);h.modal('iPhone Remote','Checking connection…',[{label:'Back',act:remotes}]);execute('sync').catch(function(e){updateError=e.message;}).then(function(){return execute('status');}).then(function(s){if(id!==epoch||!active())return;display(s,updateError);watch(id,s);}).catch(function(e){if(id===epoch)fail(e);});}
  function visibility(){
    clearTimeout(timer);
    if(document.hidden||!active()||!last)return;
    var id=epoch;
    execute('status').then(function(s){if(id!==epoch||!active()||document.hidden)return;display(s,updateError);watch(id,s);}).catch(function(){if(id===epoch&&active())watch(id,last);});
  }
  function remotes(){epoch++;clearTimeout(timer);h.modal('Remotes','Control OmarchyTV from your phone',[{label:'iPhone Remote',right:last?(last.paired&&last.running?'Connected':last.enabled?'Enabled':'Off'):'›',act:open},{label:'Close',act:close}]);}
  function sync(){if(!supported()||checking)return;checking=true;execute('sync').then(function(s){last=s;},function(){}).then(function(){checking=false;});}
  root.OmarchyRemote={attach:function(bindings){h=bindings;if(!visibilityBound){document.addEventListener('visibilitychange',visibility);visibilityBound=true;}sync();},supported:supported,open:open,settings:remotes};
})(window);
