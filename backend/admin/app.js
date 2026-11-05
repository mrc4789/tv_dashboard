'use strict';
const $ = id => document.getElementById(id);
let devicePage=1, deviceTotal=0, deviceTimer=null, deviceRequest=0, deviceController=null, deviceFilters={};
let csrf = '', departments = [], selected = null, page = 1, total = 0, mode = 'config';
const icons = {'qa-qc':'QA','dev':'DV','project-teams':'PM','techgovernance':'TG'};
const date = value => value ? new Date(value).toLocaleString('vi-VN') : 'Chưa cập nhật';
function message(id,text,isError=false){$(id).textContent=text;$(id).className='message '+(isError?'error':'success');}
async function api(path, options={}){
  const headers = {...(options.body?{'Content-Type':'application/json'}:{}), ...(options.method && options.method!=='GET'?{'X-CSRF-Token':csrf}:{}), ...options.headers};
  const response = await fetch(path,{credentials:'same-origin',...options,headers});
  let result;try{result=await response.json();}catch{throw new Error('Server trả phản hồi không hợp lệ. Kiểm tra cửa sổ Start-Service.cmd.');}
  if(!response.ok){const error=new Error(result.error?.message || 'Không thực hiện được yêu cầu.');error.status=response.status;error.code=result.error?.code;if(response.status===401 && result.error?.code==='UNAUTHENTICATED'){await showLogin('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');}throw error;}
  return result;
}
function setView(id){if(id!=='app-view')stopDeviceMonitor();for(const view of ['login-view','app-view','loading-view'])$(view).hidden=view!==id;}
async function showLogin(text=''){
  setView('login-view');$('password').value='';$('current-password').value='';$('new-password').value='';$('confirm-password').value='';message('login-error',text,!!text);
  const result=await api('/admin/api/session');csrf=result.csrfToken;
}
function node(tag,text,cls){const element=document.createElement(tag);if(text!==undefined)element.textContent=text;if(cls)element.className=cls;return element;}
function validDashboardLink(value){try{const u=new URL(value);return ['http:','https:'].includes(u.protocol)&&!u.username&&!u.password?u.href:null;}catch{return null;}}
function renderDepartments(){
  const container=$('department-list');container.replaceChildren();
  for(const dep of departments){
    const card=node('button',undefined,'department-card'+(selected?.id===dep.id?' selected':''));card.type='button';card.setAttribute('aria-pressed',String(selected?.id===dep.id));
    card.append(node('span',icons[dep.id] || 'TV','dept-icon'));const body=node('div');body.append(node('strong',dep.name),node('small',dep.id),node('span',`${dep.dashboardUrl?.includes('/sample-dashboard/')?'Dashboard mẫu':'URL đã cấu hình'} · v${dep.version}`,'meta'));card.append(body);
    card.addEventListener('click',()=>{if(selected && $('dashboard-url').value.trim()!==selected.dashboardUrl && !window.confirm('Bạn có nội dung chưa lưu. Chuyển phòng ban và bỏ nội dung đang nhập?'))return;choose(dep);});container.append(card);
  }
  $('department-count').textContent=departments.length;$('configured-count').textContent=departments.filter(d=>d.dashboardUrl).length;
}
function choose(dep){
  $('cloud-code').textContent='';
  selected=dep;renderDepartments();$('department-name').textContent=dep.name;$('department-description').textContent=dep.description;$('version-badge').textContent='v'+dep.version;$('dashboard-url').value=dep.dashboardUrl||'';
  $('updated-info').textContent=`${date(dep.updatedAt)} · ${dep.updatedBy || 'Chưa có'}`;
  const link=validDashboardLink(dep.dashboardUrl);if(link){$('open-dashboard').href=link;$('open-dashboard').hidden=false;}else{$('open-dashboard').removeAttribute('href');$('open-dashboard').hidden=true;}
  message('config-message','');loadRecent().catch(error=>message('config-message',error.message,true));
}
async function loadDepartments(preferId){
  const result=await api('/admin/api/departments');departments=result.items;
  const oldFilter=$('history-filter').value;$('history-filter').replaceChildren(new Option('Tất cả phòng ban',''));
  departments.forEach(d=>$('history-filter').add(new Option(d.name,d.id)));$('history-filter').value=oldFilter;
  const deviceFilter=$('devices-department').value;$('devices-department').replaceChildren(new Option('Tất cả phòng ban',''));departments.forEach(d=>$('devices-department').add(new Option(d.name,d.id)));$('devices-department').value=deviceFilter;
  const dep=departments.find(d=>d.id===(preferId||selected?.id))||departments[0];
  if(dep)choose(dep);else{$('department-list').replaceChildren(node('p','Chưa có phòng ban.','empty'));$('save-button').disabled=true;}
}
function auditTable(items){
  if(!items.length)return node('p','Chưa có thay đổi cấu hình.','empty');
  const wrap=node('div',undefined,'table-scroll'),table=node('table'),head=node('thead'),hr=node('tr');for(const title of ['PHÒNG BAN / THỜI ĐIỂM','URL DASHBOARD','PHIÊN BẢN','NGƯỜI SỬA'])hr.append(node('th',title));head.append(hr);table.append(head);
  const body=node('tbody');for(const item of items){const tr=node('tr'),first=node('td');first.append(node('strong',item.department_name||item.department_id),node('div',date(item.changed_at),'muted'));const urls=node('td',undefined,'url-cell');urls.append(node('del',item.old_url||'Chưa có URL'),node('span',item.new_url));tr.append(first,urls,node('td',`v${item.old_version} → v${item.new_version}`),node('td',item.changed_by));body.append(tr);}table.append(body);wrap.append(table);return wrap;
}
async function loadRecent(){
  const id=selected?.id;if(!id)return;const result=await api('/admin/api/audit?department='+encodeURIComponent(id)+'&pageSize=4');if(selected?.id===id)$('recent-history').replaceChildren(auditTable(result.items));
}
async function loadHistory(){
  const filter=$('history-filter').value;const result=await api('/admin/api/audit?page='+page+'&pageSize=10'+(filter?'&department='+encodeURIComponent(filter):''));total=result.total;
  $('history-table').replaceChildren(auditTable(result.items));$('history-total').textContent=total+' thay đổi';$('page-number').textContent=`Trang ${page} / ${Math.max(1,Math.ceil(total/10))}`;$('previous-page').disabled=page===1;$('next-page').disabled=page*10>=total;
}
function navigate(next){
 stopDeviceMonitor();mode=next;
 for(const m of ['config','history','devices','account']){$(m+'-view').hidden=m!==next;$('nav-'+m).classList.toggle('active',m===next);}
 $('breadcrumb').textContent='Quản trị / '+({config:'Phòng ban',history:'Lịch sử thay đổi',devices:'Theo dõi TV',account:'Tài khoản'}[next]);message('global-message','');
 if(next==='history')loadHistory().catch(e=>message('global-message',e.message,true));
 if(next==='devices'){readDeviceFilters();loadDevices();}
}
async function enter(result){$('cloud-code').textContent='';$('cloud-tools').hidden=!result.cloudPairing;csrf=result.csrfToken;$('user-name').textContent=result.username;setView('app-view');$('url-hint').textContent=result.developmentHttp?'URL đầy đủ bắt đầu bằng http:// hoặc https://. HTTP chỉ dành cho bản thử nghiệm nội bộ.':'URL đầy đủ bắt đầu bằng https://.';await loadDepartments();navigate('config');}
$('login-form').addEventListener('submit',async event=>{event.preventDefault();$('login-button').disabled=true;message('login-error','');try{const result=await api('/admin/api/login',{method:'POST',body:JSON.stringify({username:$('username').value.trim(),password:$('password').value})});$('password').value='';const session=await api('/admin/api/session');await enter(session);}catch(error){message('login-error',error.message,true);}finally{$('login-button').disabled=false;}});
$('logout-button').addEventListener('click',async()=>{try{await api('/admin/api/logout',{method:'POST',body:'{}'});await showLogin();}catch(error){message('global-message',error.message,true);}});
$('config-form').addEventListener('submit',async event=>{
 event.preventDefault();if(!selected)return;const dep=selected;const url=$('dashboard-url').value.trim();$('save-button').disabled=true;message('config-message','Đang lưu…');
 try{const result=await api('/api/v1/departments/'+encodeURIComponent(dep.id)+'/settings',{method:'PUT',body:JSON.stringify({dashboardUrl:url,expectedVersion:dep.version})});await loadDepartments(dep.id);message('config-message',result.version===dep.version?'URL không thay đổi; giữ nguyên phiên bản.':`Đã lưu phiên bản v${result.version}. API đã trả URL mới; TV ở chế độ máy chủ sẽ áp dụng trong chu kỳ đồng bộ khi có kết nối.`);}
 catch(error){message('config-message',error.status===409?error.message+' Nội dung bạn nhập vẫn được giữ. Bấm Tải lại để xem bản mới trước khi sửa tiếp.':error.message,true);}
 finally{$('save-button').disabled=false;}
});
$('validate-button').addEventListener('click',async()=>{const button=$('validate-button');button.disabled=true;try{const result=await api('/admin/api/validate-url',{method:'POST',body:JSON.stringify({dashboardUrl:$('dashboard-url').value})});message('config-message',result.message);}catch(error){message('config-message',error.message,true);}finally{button.disabled=false;}});
$('reset-button').addEventListener('click',()=>{if(selected)choose(selected);});
$('refresh-button').addEventListener('click',async()=>{if(selected && $('dashboard-url').value.trim()!==selected.dashboardUrl&&!window.confirm('Tải lại sẽ thay nội dung chưa lưu bằng cấu hình server. Tiếp tục?'))return;$('refresh-button').disabled=true;try{await loadDepartments();}catch(e){message('global-message',e.message,true);}finally{$('refresh-button').disabled=false;}});
for(const name of ['config','history','devices','account'])$('nav-'+name).addEventListener('click',()=>navigate(name));
$('all-history').addEventListener('click',()=>{$('history-filter').value=selected?.id||'';page=1;navigate('history');});
$('history-filter').addEventListener('change',()=>{page=1;loadHistory().catch(e=>message('global-message',e.message,true));});
$('previous-page').addEventListener('click',()=>{if(page>1){page--;loadHistory().catch(e=>message('global-message',e.message,true));}});
$('next-page').addEventListener('click',()=>{if(page*10<total){page++;loadHistory().catch(e=>message('global-message',e.message,true));}});
$('password-form').addEventListener('submit',async event=>{event.preventDefault();if($('new-password').value!==$('confirm-password').value){message('password-message','Mật khẩu nhập lại chưa khớp.',true);return;}const button=event.currentTarget.querySelector('button');button.disabled=true;try{await api('/admin/api/password',{method:'POST',body:JSON.stringify({currentPassword:$('current-password').value,newPassword:$('new-password').value})});await showLogin('Đã đổi mật khẩu. Đăng nhập lại bằng mật khẩu mới.');}catch(e){message('password-message',e.message,true);}finally{button.disabled=false;}});
function stopDeviceMonitor(){
 if(deviceTimer!==null){clearTimeout(deviceTimer);deviceTimer=null;}
 deviceRequest++;if(deviceController){deviceController.abort();deviceController=null;}
}
function readDeviceFilters(){deviceFilters={department:$('devices-department').value,status:$('devices-status').value,archived:$('devices-archive').value||'false',q:$('devices-search').value.trim()};}
function scheduleDeviceRefresh(){
 if(deviceTimer!==null)clearTimeout(deviceTimer);
 deviceTimer=null;
 if(mode==='devices' && !$('app-view').hidden && $('devices-auto').checked && !document.hidden){deviceTimer=setTimeout(()=>{deviceTimer=null;loadDevices();},30000);}
}
function relativeSeen(value,serverTime){
 const seconds=Math.max(0,Math.floor((Date.parse(serverTime)-Date.parse(value))/1000));
 if(!Number.isFinite(seconds))return '';
 if(seconds<60)return 'Vừa xuất hiện';if(seconds<3600)return Math.floor(seconds/60)+' phút trước';if(seconds<86400)return Math.floor(seconds/3600)+' giờ trước';return Math.floor(seconds/86400)+' ngày trước';
}
function secondsLabel(seconds){return seconds%60===0?(seconds/60)+' phút':seconds+' giây';}
function deviceTable(items,serverTime){
 if(!items.length)return node('p',Object.values(deviceFilters).some(v=>v && v!=='false')?'Không có TV khớp bộ lọc.':'Chưa thấy TV. Chọn Máy chủ API trong app và Đồng bộ ngay để TV xuất hiện.','empty');
 const wrap=node('div',undefined,'table-scroll'),table=node('table'),head=node('thead'),header=node('tr');
 for(const text of ['TÊN TV / DEVICE ID','PHÒNG BAN','TRẠNG THÁI','LẦN CUỐI THẤY','BẢN APP','PIN ĐIỆN THOẠI'])header.append(node('th',text));head.append(header);table.append(head);
 const body=node('tbody');for(const item of items){
  const row=node('tr'),name=node('td',undefined,'device-name-cell');name.append(node('strong',item.name),node('div',item.deviceId,'device-id'));
  const dep=node('td');dep.append(node('strong',item.department.name),node('div',item.department.id,'muted'));
  const status=node('td');status.append(node('span',item.status==='online'?'Online':'Offline','device-state '+(item.status==='online'?'state-online':'state-offline')));
  const load=item.dashboardLoad; if(load)status.append(node('div',({unknown:'Chưa xác nhận tải',loading:'Đang tải trang',loaded:'WebView đã báo tải xong',error:'Tải trang lỗi'}[load.state]||'Chưa xác nhận tải'),'muted'),node('div','Báo cáo: '+date(load.observed_at),'muted')); else status.append(node('div','Chưa có báo cáo tải','muted'));
  const seen=node('td');seen.append(node('div',date(item.lastSeenAt)),node('div',relativeSeen(item.lastSeenAt,serverTime),'muted'));if(item.archivedAt)seen.append(node('div','Lưu trữ: '+date(item.archivedAt),'muted'));
  const pinCell=node('td'),pinButton=node('button','Đặt PIN','secondary');pinButton.type='button';pinButton.onclick=()=>openPinDialog(item);const logButton=node('button','Log lỗi','secondary');logButton.type='button';logButton.onclick=()=>openDiagnostics(item);pinCell.append(pinButton,logButton);
  if(item.hasCloudDeviceKey){
   const revoke=node('button','Thu hồi khóa TV','secondary');revoke.type='button';
   revoke.onclick=async()=>{if(!window.confirm('Ngừng cho '+item.name+' kết nối cloud?'))return;
     revoke.disabled=true;
     try{await api('/admin/api/devices/'+encodeURIComponent(item.deviceId)+'/revoke-cloud',{method:'POST',body:'{}'});message('devices-message','Đã thu hồi khóa TV. Cần mã ghép mới để kết nối lại.');}
     catch(e){message('devices-message',e.message,true);revoke.disabled=false;}};
   pinCell.append(revoke);
  }
  row.append(name,dep,status,seen,node('td',item.appVersion||'—'),pinCell);body.append(row);
 }table.append(body);wrap.append(table);return wrap;
}
async function loadDevices(){
 if(mode!=='devices'||$('app-view').hidden)return;
 if(deviceTimer!==null){clearTimeout(deviceTimer);deviceTimer=null;}
 if(deviceController)deviceController.abort();deviceController=new AbortController();
 const request=++deviceRequest;const controller=deviceController;const params=new URLSearchParams({page:String(devicePage),pageSize:'20'});
 for(const [key,value] of Object.entries(deviceFilters))if(value)params.set(key,value);
 $('devices-refresh').disabled=true;message('devices-message','Đang lấy danh sách TV…');
 try{
  const result=await api('/api/v1/devices?'+params.toString(),{signal:controller.signal});if(request!==deviceRequest||mode!=='devices')return;
  if(!result.summary || !Number.isInteger(result.summary.total) || !Number.isInteger(result.summary.online) || !Number.isInteger(result.summary.offline) || result.summary.total!==result.summary.online+result.summary.offline)throw new Error('Backend chưa trả dữ liệu theo dõi đầy đủ. Cập nhật backend/server/config_service.py của Phase 4 rồi khởi động lại server.');
  deviceTotal=result.total;
  const lastPage=Math.max(1,Math.ceil(deviceTotal/20));if(devicePage>lastPage){devicePage=lastPage;await loadDevices();return;}
  $('devices-table').replaceChildren(deviceTable(result.items,result.serverTime));
  const summary=result.summary;
  $('devices-count').textContent=summary.total;$('devices-online').textContent=summary.online;$('devices-offline').textContent=summary.offline;
  $('devices-total-label').textContent=deviceFilters.archived==='true'?'TV đã lưu trữ':'TV đang theo dõi';
  $('devices-page').textContent='Trang '+devicePage+' / '+lastPage+' · '+result.total+' TV khớp trạng thái';
  $('devices-previous').disabled=devicePage===1;$('devices-next').disabled=devicePage*20>=deviceTotal;
  $('devices-last-refresh').textContent='Cập nhật: '+date(result.serverTime);
  $('devices-threshold-hint').textContent='TV hỏi API khoảng '+secondsLabel(result.pollSeconds||300)+'/lần; quá '+secondsLabel(result.offlineSeconds||720)+' không thấy thì coi là offline. Trạng thái này không xác nhận dashboard đang hiển thị tốt.';
  message('devices-message','');
 }catch(error){if(request===deviceRequest&&mode==='devices'&&error.name!=='AbortError')message('devices-message',error.message+' Danh sách cũ chưa được xác nhận lại; xem thời điểm cập nhật.',true);}
 finally{if(request===deviceRequest){$('devices-refresh').disabled=false;deviceController=null;scheduleDeviceRefresh();}}
}
function applyDeviceFilters(){devicePage=1;readDeviceFilters();loadDevices();}
$('devices-filter-form').addEventListener('submit',event=>{event.preventDefault();applyDeviceFilters();});
for(const id of ['devices-department','devices-status','devices-archive'])$(id).addEventListener('change',applyDeviceFilters);
$('devices-refresh').addEventListener('click',()=>loadDevices());
$('devices-auto').addEventListener('change',()=>scheduleDeviceRefresh());
$('devices-previous').addEventListener('click',()=>{if(devicePage>1){devicePage--;loadDevices();}});
$('devices-next').addEventListener('click',()=>{if(devicePage*20<deviceTotal){devicePage++;loadDevices();}});
document.addEventListener('visibilitychange',()=>{if(document.hidden)stopDeviceMonitor();else if(mode==='devices'&&!$('app-view').hidden)loadDevices();});
(async()=>{try{const result=await api('/admin/api/session');csrf=result.csrfToken;if(result.authenticated)await enter(result);else setView('login-view');}catch(error){setView('login-view');message('login-error',error.message,true);}})();

let pinDevice=null,pinRequest=0;
function pinModeChanged(){const custom=$('pin-mode').value==='custom';$('pin-fields').hidden=!custom;$('managed-pin').required=custom;$('managed-pin-confirm').required=custom;}
async function openPinDialog(item){
 const request=++pinRequest;pinDevice=item.deviceId;$('pin-device').textContent=item.name+' · '+item.deviceId;$('managed-pin').value='';$('managed-pin-confirm').value='';$('pin-message').textContent='Đang tải…';$('pin-save').disabled=true;$('pin-dialog').showModal();
 try{const r=await api('/admin/api/devices/'+encodeURIComponent(item.deviceId)+'/phone-pin');if(request!==pinRequest||pinDevice!==item.deviceId)return;$('pin-mode').value=r.mode;pinModeChanged();$('pin-message').textContent=(r.mode==='custom'?'PIN do admin quản lý':'PIN ngẫu nhiên')+(r.tvApplied?' · TV đã nhận phiên bản '+r.version:' · TV chưa xác nhận cấu hình mới');$('pin-save').disabled=false;}catch(e){if(request===pinRequest)$('pin-message').textContent=e.message;}
}
$('pin-mode').onchange=pinModeChanged;
$('pin-close').onclick=()=>$('pin-dialog').close();
$('pin-dialog').addEventListener('close',()=>{$('managed-pin').value='';$('managed-pin-confirm').value='';pinDevice=null;pinRequest++;});
$('pin-form').onsubmit=async e=>{e.preventDefault();const mode=$('pin-mode').value,pin=$('managed-pin').value;if(mode==='custom'&&(!/^[0-9]{6,8}$/.test(pin)||pin!==$('managed-pin-confirm').value)){$('pin-message').textContent='Nhập PIN 6–8 chữ số và xác nhận giống nhau.';return;}
 const request=pinRequest,device=pinDevice;$('pin-save').disabled=true;try{const result=await api('/admin/api/devices/'+encodeURIComponent(device)+'/phone-pin',{method:'PUT',body:JSON.stringify(mode==='custom'?{mode,pin}:{mode})});if(request!==pinRequest)return;$('pin-message').textContent=result.message;$('managed-pin').value='';$('managed-pin-confirm').value='';}catch(e){if(request===pinRequest)$('pin-message').textContent=e.message;}finally{if(request===pinRequest)$('pin-save').disabled=false;}
};

async function openDiagnostics(item){
 const dialog=document.createElement('dialog');dialog.style.cssText='max-width:800px;width:90%;max-height:80vh;overflow:auto';
 const title=node('h2','Log lỗi · '+item.name),note=node('p','Chỉ thu thập sau khi TV xác nhận đã nhận chính sách. Sự kiện được gửi sớm theo lô; khi offline, hàng đợi trên TV được giữ tối đa 7 ngày. “WebView báo tải xong” không xác nhận đăng nhập hay dữ liệu nghiệp vụ của website. Thời gian xảy ra và thời gian server nhận được hiển thị riêng. Tối đa 250 log/TV, lưu 7 ngày; hiển thị 100 log mới nhất.'),status=node('p'),list=node('div'),toggle=node('button','Đang tải…'),refresh=node('button','Làm mới'),clear=node('button','Xóa log'),close=node('button','Đóng');
 let enabled=false,busy=false;
 const path='/api/v1/devices/'+encodeURIComponent(item.deviceId)+'/diagnostics';
 async function perform(options){if(busy)return;busy=true;toggle.disabled=refresh.disabled=clear.disabled=true;try{const result=await api(path,options);if(!dialog.isConnected)return;enabled=result.enabled;toggle.textContent=enabled?'Tắt gửi log':'Bật gửi log';status.textContent='Server: '+(enabled?'đã bật':'đã tắt')+' · TV: '+(result.tvAcknowledged?'đã xác nhận phiên bản '+result.policyVersion+' lúc '+date(result.acknowledgedAt):'chưa xác nhận chính sách server mới nhất')+(enabled?'':' · log chờ trên TV sẽ bị xóa khi TV nhận chính sách tắt');if(!options){list.replaceChildren();for(const e of result.items){const kind=e.kind==='LOADED'?'WebView báo tải xong':e.kind;list.append(node('p','Xảy ra '+date(e.occurred_at)+' · nhận '+date(e.received_at)+' · '+kind+(e.code?' · HTTP/mã '+e.code:'')));}if(!result.items.length)list.append(node('p','Chưa có log.'));}}catch(e){if(dialog.isConnected)status.textContent=e.message;}finally{busy=false;toggle.disabled=refresh.disabled=clear.disabled=false;}}
 toggle.onclick=async()=>{await perform({method:'PUT',body:JSON.stringify({enabled:!enabled})});};refresh.onclick=()=>perform();clear.onclick=async()=>{if(confirm('Xóa log lỗi của TV này?')){await perform({method:'DELETE'});await perform();}};close.onclick=()=>dialog.close();dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.append(title,note,status,toggle,refresh,clear,list,close);document.body.append(dialog);dialog.showModal();await perform();
}
$('cloud-issue').addEventListener('click',async()=>{
 if(!selected)return;
 const dep=selected.id, button=$('cloud-issue');button.disabled=true;
 try{const v=await api('/admin/api/cloud-codes',{method:'POST',body:JSON.stringify({department:dep})});
 if(selected?.id===dep)$('cloud-code').textContent=v.code+' · '+dep+' · dùng cho 1 TV trong 10 phút';}
 catch(e){message('config-message',e.message,true);}finally{button.disabled=false;}
});
