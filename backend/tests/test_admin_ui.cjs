/* Frontend logic regression with a minimal DOM/fetch harness; not browser rendering QA. */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
class Element{
 constructor(tag='div',text='',value=''){this.tag=tag;this.textContent=text;this.value=value;this.children=[];this.events={};this.hidden=false;this.disabled=false;this.classList={toggle(){}};}
 append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}add(node){this.children.push(node);}
 addEventListener(name,fn){this.events[name]=fn;}setAttribute(name,value){this[name]=value;}removeAttribute(name){delete this[name];}
 querySelector(){return this.button||(this.button=new Element('button'));}
 set innerHTML(value){throw new Error('Unsafe innerHTML used');}
}
const html=fs.readFileSync(path.join(__dirname,'../admin/index.html'),'utf8'),ids=[...html.matchAll(/id="([^"]+)"/g)].map(m=>m[1]),dom=Object.fromEntries(ids.map(id=>[id,new Element()]));
let logged=false,conflict=false;const calls=[];
const deps=['qa-qc','dev','project-teams','techgovernance'].map((id,n)=>({id,name:n===0?'<script>example</script>':id,description:'Description',dashboardUrl:'https://example.com/'+id,version:1,updatedBy:'sample-seed',updatedAt:'2026-10-13T00:00:00Z'}));
const context={document:{getElementById:id=>{assert.ok(dom[id],'Missing HTML id: '+id);return dom[id];},createElement:tag=>new Element(tag),addEventListener(){}},Option:class extends Element{constructor(text,value){super('option',text,value);}},window:{confirm:()=>true},URL,console,setTimeout,fetch:async(url,opt)=>{
 calls.push({url,opt});let status=200,body;
 if(url==='/admin/api/session')body={authenticated:logged,username:'mentor',csrfToken:logged?'session-csrf':'login-csrf',developmentHttp:true};
 else if(url==='/admin/api/login'){const data=JSON.parse(opt.body);assert.equal(data.username,'mentor');assert.equal(opt.headers['X-CSRF-Token'],'login-csrf');logged=true;body={authenticated:true,csrfToken:'session-csrf'};}
 else if(url==='/admin/api/departments')body={items:deps.map(x=>({...x}))};
 else if(url.startsWith('/admin/api/audit'))body={items:[],total:0};
 else if(url.startsWith('/api/v1/departments/')){const data=JSON.parse(opt.body);assert.equal(opt.headers['X-CSRF-Token'],'session-csrf');if(conflict){status=409;body={error:{code:'VERSION_CONFLICT',message:'Version conflict'}};}else{const d=deps[0];assert.equal(data.expectedVersion,d.version);d.dashboardUrl=data.dashboardUrl;d.version++;d.updatedBy='mentor';body={version:d.version};}}
 else if(url==='/admin/api/logout'){logged=false;body={ok:true};}else throw new Error('Unexpected route '+url);
 return{ok:status===200,status,json:async()=>body};
}};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'../admin/app.js'),'utf8'),context);
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 await flush();assert.equal(dom['login-view'].hidden,false);
 dom.username.value='mentor';dom.password.value='password';await dom['login-form'].events.submit({preventDefault(){}});
 assert.equal(dom['app-view'].hidden,false);assert.equal(dom['department-list'].children.length,4);assert.equal(dom['department-name'].textContent,'<script>example</script>');assert.equal(dom.password.value,'');
 dom['dashboard-url'].value='https://example.com/new';await dom['config-form'].events.submit({preventDefault(){}});
 assert.equal(deps[0].version,2);assert.match(dom['config-message'].textContent,/v2/);assert.equal(dom['open-dashboard'].href,'https://example.com/new');
 conflict=true;dom['dashboard-url'].value='https://example.com/unsaved';await dom['config-form'].events.submit({preventDefault(){}});
 assert.equal(dom['dashboard-url'].value,'https://example.com/unsaved');assert.match(dom['config-message'].textContent,/Nội dung bạn nhập vẫn được giữ/);assert.equal(dom['save-button'].disabled,false);
 await dom['logout-button'].events.click();assert.equal(dom['login-view'].hidden,false);assert.equal(logged,false);
 assert.ok(calls.some(c=>c.url.startsWith('/api/v1/departments/')));console.log('Frontend logic: login, four departments, text-only rendering, save, conflict draft retention and logout passed. Not a browser/layout test.');
})().catch(e=>{console.error(e);process.exitCode=1;});
