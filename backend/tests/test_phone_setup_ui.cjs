// Execute the inline setup script that the APK serves. No mocked copy of its logic.
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const src=fs.readFileSync(path.resolve(__dirname,'../../app/src/main/java/com/example/tvdashboard/net/ConfigServer.kt'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
function setup(mode,replies) {
 const nodes={},calls=[];const el=id=>nodes[id]??={hidden:false,value:id==='pin'?'1234':id==='mode'?mode:'',events:{},focus(){},addEventListener(k,f){this.events[k]=f;}};
 vm.runInNewContext(src,{document:{getElementById:el},URLSearchParams,fetch:async (path,opt)=>{
   calls.push(path);const r=replies[path];assert.ok(r,'unexpected '+path);
   return {ok:!r.error,text:async()=>JSON.stringify(r)}
 }});
 return {el,calls,unlock:el('unlock').events.click};
}
test('PIN wrong: neither manual nor cloud enrollment opens',async()=>{
 const t=setup('cloud',{'/unlock':{error:'Sai PIN'}});await t.unlock();
 assert.ok(t.el('form').hidden && t.el('cloud-panel').hidden);assert.deepEqual(t.calls,['/unlock']);
});
test('PIN valid, LAN absent: manual fields accessible, switching to Render requires no second unlock',async()=>{
 const t=setup('remote',{'/unlock':{unlocked:true},'/pairing':{error:'Không thấy LAN'},'/cloud':{message:'Đã lưu'}});
 await t.unlock();assert.equal(t.el('form').hidden,false);assert.equal(t.el('server').disabled,false);
 t.el('mode').value='cloud';t.el('mode').events.change();assert.equal(t.el('cloud-panel').hidden,false);
 t.el('cloud-code').value='123456ABCDEF';await t.el('cloud-submit').events.click();
 assert.deepEqual(t.calls,['/unlock','/pairing','/cloud']);assert.equal(t.el('status').textContent,'Đã lưu');
});
test('Render and local URL do not probe LAN',async()=>{
 for(const mode of ['cloud','local']){
 const t=setup(mode,{'/unlock':{unlocked:true,hasCloud:true}});await t.unlock();assert.deepEqual(t.calls,['/unlock']);
 assert.equal(t.el(mode==='cloud'?'cloud-panel':'form').hidden,false);
 }
});
