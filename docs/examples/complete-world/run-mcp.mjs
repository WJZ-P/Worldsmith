// Reusable same-MCP workflow driver. An AI authors the source kit from prompt.txt;
// this file only executes and resumes those explicit tool calls, never fabricates a native receipt.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {makeCreatureTexture} from './make-creature-texture.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
const options={};for(let i=2;i<process.argv.length;i+=2){if(!process.argv[i].startsWith('--')||!process.argv[i+1])throw Error('Use --key value arguments');options[process.argv[i].slice(2)]=process.argv[i+1];}
const out=path.resolve(options.out||path.join(here,'../../../build/complete-world-polish/run'));
fs.mkdirSync(out,{recursive:true});
const stateFile=path.join(out,'state.json');
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const save=(p,v)=>{
 const destination=path.join(out,p);fs.mkdirSync(path.dirname(destination),{recursive:true});
 const pending=destination+'.pending';fs.writeFileSync(pending,JSON.stringify(v,null,2));
 // A short-lived reader or scanner can deny Windows replacement; never delete the last good state.
 for(let attempt=0;;attempt++)try{fs.renameSync(pending,destination);return;}catch(error){
  if(!['EPERM','EACCES','EBUSY'].includes(error.code)||attempt>=7)throw error;
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,25*(attempt+1));
 }
};
const digest=v=>crypto.createHash('sha256').update(typeof v==='string'?v:JSON.stringify(v)).digest('hex');
const state=fs.existsSync(stateFile)?json(stateFile):{assets:{},creatures:{},drawings:{}};
const persist=()=>{const {remoteAssets,...saved}=state;save('state.json',saved);};
let toolNames;
function endpoint(){if(options.endpoint)return options.endpoint;const d=json(path.resolve(options.discovery||path.join(here,'../../../build/complete-world-polish/authoring-host/mcp.json')));if(d.running===false)throw Error('The discovered host is stopped; restart it or choose a live endpoint');return d.url;}
async function rpc(method,params={}){
  const response=await fetch(endpoint(),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:Date.now(),method,params}),signal:AbortSignal.timeout(180000)});
  const body=await response.json();if(!response.ok||body.error)throw Error(JSON.stringify(body.error||body));return body.result;
}
async function call(name,args={},tag=name,{allowError=false}={}){
  const key=tag.replace(/[^a-zA-Z0-9_.-]/g,'_');save(`requests/${key}.json`,{name,arguments:args});
  const reply=await rpc('tools/call',{name,arguments:args});const images=[];
  for(const item of reply.content||[])if(item.type==='image'){const p=`previews/${key}-${images.length}.png`;fs.mkdirSync(path.join(out,'previews'),{recursive:true});fs.writeFileSync(path.join(out,p),Buffer.from(item.data,'base64'));images.push(p);}
  const record={structuredContent:reply.structuredContent||{},isError:!!reply.isError,text:(reply.content||[]).filter(i=>i.type==='text').map(i=>i.text).join('\n'),images};save(`responses/${key}.json`,record);
  console.log(JSON.stringify({tool:name,tag:key,isError:record.isError,stage:record.structuredContent.stage,revision:record.structuredContent.revision,images}));
  if(record.isError&&!allowError)throw Error(record.text);
  return record;
}
async function revision(){const r=await call('worldsmith_get_content_draft',{sessionId:state.sessionId},'current-draft');state.revision=r.structuredContent.revision;state.remoteAssets=new Set((r.structuredContent.assets||[]).map(a=>a.id));return state.revision;}
function resolve(value){if(typeof value==='string'&&value.startsWith('@asset:')){const id=value.slice(7);if(!state.assets[id])throw Error(`Missing asset ${id}`);return state.assets[id].id;}if(typeof value==='string'&&value.startsWith('@drawing:')){const id=value.slice(9);if(!state.drawings[id])throw Error(`Missing drawing ${id}`);return state.drawings[id].drawingId;}if(Array.isArray(value))return value.map(resolve);if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,resolve(v)]));return value;}
const kit=json(path.join(here,'kit.json'));
async function begin(){
  toolNames=new Set((await rpc('tools/list')).tools.map(t=>t.name));
  if(state.sessionId){await call('worldsmith_resume_session',{sessionId:state.sessionId,detail:'summary'},'resume');return;}
  const args={prompt:fs.readFileSync(path.join(here,kit.prompt),'utf8').trim(),detail:'summary'};
  if(toolNames.has('worldsmith_put_world_design_plan'))args.mode='COMPLETE_WORLD';
  const r=await call('worldsmith_begin_world',args,'begin');state.sessionId=r.structuredContent.sessionId;state.prompt=args.prompt;persist();
}
async function plan(){const p=path.join(here,kit.worldDesignPlan);if(!fs.existsSync(p)||!toolNames.has('worldsmith_put_world_design_plan'))return;const design=json(p),hash=digest(design);if(state.planHash===hash)return;const r=await call('worldsmith_put_world_design_plan',{sessionId:state.sessionId,expectedRevision:await revision(),mode:'COMPLETE_WORLD',plan:design},'design-plan');state.planHash=hash;state.revision=r.structuredContent.revision;persist();}
async function textures(){const dir=path.join(here,kit.textureDirectory);if(!fs.existsSync(dir))throw Error('Texture recipes are not ready');await revision();for(const f of fs.readdirSync(dir).filter(f=>f.endsWith('.json')).sort()){const id=f.slice(0,-5),recipe=json(path.join(dir,f)),hash=digest(recipe);if(state.assets[id]?.inputHash===hash&&state.remoteAssets.has(state.assets[id].id))continue;const r=await call('worldsmith_build_texture',{sessionId:state.sessionId,expectedRevision:await revision(),recipe},`texture-${id}`);state.assets[id]={id:r.structuredContent.asset.id,inputHash:hash};state.revision=r.structuredContent.revision;persist();}}
async function registerSources(){
 const spec=json(path.join(here,kit.structureTargets)),directory=path.join(here,spec.sourceDirectory),changes={};
 for(const file of [...new Set(Object.values(spec.targets).flatMap(t=>t.sources))].sort())changes[file]=fs.readFileSync(path.join(directory,file),'utf8');
 const targets=Object.fromEntries(Object.entries(spec.targets).map(([name,t])=>[name,{entryClass:t.entrypoint,files:t.sources}]));
 const hash=digest({changes,targets});if(state.sourceHash!==hash){const r=await call('worldsmith_put_drawing_source',{sessionId:state.sessionId,name:spec.projectName,expectedRevision:state.sourceRevision||0,changes,targets},'source-project');state.projectId=r.structuredContent.projectId;state.sourceRevision=r.structuredContent.revision;state.sourceHash=hash;persist();}return spec;
}
async function drawings(){
 const spec=await registerSources(),only=options.targets?.split(',');
 for(const [name,t]of Object.entries(spec.targets)){if(only&&!only.includes(name))continue;const signature=digest({revision:state.sourceRevision,target:name,parameters:t.parameters,seed:spec.seed});
  if(state.drawings[name]?.inputHash===signature)continue;
  let jobId=state.drawings[name]?.pendingInputHash===signature?state.drawings[name].jobId:null;
  if(!jobId){const r=await call('worldsmith_build_drawing',{sessionId:state.sessionId,name,requestId:`${name}-${signature.slice(0,20)}`,sourceRef:{projectId:state.projectId,revision:state.sourceRevision,target:name},parameters:t.parameters||{},seeds:[spec.seed]},`build-${name}`);jobId=r.structuredContent.jobId;state.drawings[name]={jobId,pendingInputHash:signature};persist();}
  const until=Date.now()+300000;let result;
  while(Date.now()<until){result=(await call('worldsmith_get_drawing_job',{sessionId:state.sessionId,jobId},`job-${name}`)).structuredContent;if(result.stage==='SUCCEEDED')break;if(['FAILED','CANCELLED','INTERRUPTED','WAITING_APPROVAL'].includes(result.stage))throw Error(`${name}: ${JSON.stringify(result)}`);await new Promise(r=>setTimeout(r,1000));}
  if(result?.stage!=='SUCCEEDED')throw Error(`Drawing ${name} is still pending; resume this same job, do not restart blindly`);
  state.drawings[name]={jobId,drawingId:result.drawingIds[0],inputHash:signature,checks:result.checks,statistics:result.drawings};persist();
  await call('worldsmith_preview_drawing',{sessionId:state.sessionId,drawingId:result.drawingIds[0],views:['isometric','front','top'],renderMode:'material'},`drawing-${name}`);
 }
}
async function creatures(){
 const dir=path.join(here,kit.creatureDirectory);await revision();
 for(const file of fs.readdirSync(dir).filter(f=>f.endsWith('.json')).sort()){
  const name=file.slice(0,-5),recipe=resolve(json(path.join(dir,file))),hash=digest(recipe);
  if(state.creatures[name]?.inputHash===hash&&state.remoteAssets.has(state.creatures[name].textureAsset))continue;
  const guide=await call('worldsmith_build_creature',{sessionId:state.sessionId,recipe},`creature-${name}-guide`);
  const textureRecipe=makeCreatureTexture(guide.structuredContent.uvLayout);save(`derived-textures/${name}.json`,textureRecipe);
  const textureHash=digest(textureRecipe),previousSkin=state.assets[`creature_${name}`];
  let asset=previousSkin?.inputHash===textureHash&&state.remoteAssets.has(previousSkin.id)?previousSkin.id:null;
  if(!asset){
   const texture=await call('worldsmith_build_texture',{sessionId:state.sessionId,expectedRevision:await revision(),recipe:textureRecipe},`creature-${name}-skin`);
   asset=texture.structuredContent.asset.id;
  }
  state.assets[`creature_${name}`]={id:asset,inputHash:textureHash};persist();
  const built=await call('worldsmith_build_creature',{sessionId:state.sessionId,recipe,textureAsset:asset},`creature-${name}-built`);
  state.creatures[name]={inputHash:hash,buildId:built.structuredContent.buildId,textureAsset:asset,definition:built.structuredContent.definition};persist();
  await call('worldsmith_preview_creature',{sessionId:state.sessionId,buildId:built.structuredContent.buildId,mode:'sheet'},`creature-${name}-sheet`);
  if(built.structuredContent.definition.boss)await call('worldsmith_preview_creature',{sessionId:state.sessionId,buildId:built.structuredContent.buildId,mode:'sheet',bossPhase:built.structuredContent.definition.boss.phases.length-1},`creature-${name}-final-phase`);
 }
}
async function previews(){for(const [name,d]of Object.entries(state.drawings)){if(options.targets&&!options.targets.split(',').includes(name))continue;await call('worldsmith_preview_drawing',{sessionId:state.sessionId,drawingId:d.drawingId,views:['isometric','front','isometric_back'],renderMode:'material'},`drawing-${name}`);}}
async function modules(){const directory=path.join(here,kit.moduleDirectory),values={};for(const f of fs.readdirSync(directory).filter(f=>f.endsWith('.json')).sort())values[f.slice(0,-5)]=resolve(json(path.join(directory,f)));if(Object.keys(state.creatures).length)values.creatures={schemaVersion:Object.values(state.creatures).some(c=>c.definition.boss)?2:1,creatures:Object.values(state.creatures).map(c=>c.definition)};const r=await call('worldsmith_put_content_modules',{sessionId:state.sessionId,expectedRevision:await revision(),modules:values},'content-modules');state.revision=r.structuredContent.revision;persist();}
async function architecture(){
 const architecture=resolve(json(path.join(here,kit.architecture))),raw=resolve(json(path.join(here,kit.structures))),structures=Array.isArray(raw)?raw:raw.structures;
 // Reusing a frozen older target is explicit, never a global silent stale-source bypass.
 const reuse=options['reuse-drawings']?.split(',').filter(Boolean)||[];
 for(const name of reuse)if(!state.drawings[name]?.drawingId)throw Error(`No completed drawing to reuse: ${name}`);
 const reusedIds=new Set(reuse.map(name=>state.drawings[name].drawingId));
 for(const structure of structures)for(const blueprint of [structure.blueprint,...Object.values(structure.assembly?.pieces||{})]){
  const source=blueprint.authored||blueprint.drawing;
  if(source?.variants?.some(id=>reusedIds.has(id))){
   if(!source.variants.every(id=>reusedIds.has(id)))throw Error(`Select all frozen variants explicitly when reusing ${blueprint.id}`);
   source.allowPreviousRevision=true;
  }
 }
 const r=await call('worldsmith_put_architecture_draft',{sessionId:state.sessionId,expectedRevision:await revision(),architecture,structures},'architecture-draft');
 state.revision=r.structuredContent.revision;persist();
 await call('worldsmith_validate_architecture',{sessionId:state.sessionId},'architecture-validation');
}
async function write(){
 const r=await call('worldsmith_write_pack',{sessionId:state.sessionId,expectedRevision:await revision(),displayName:kit.title,description:state.prompt},'write-pack',{allowError:true});
 state.lastWrite=r.structuredContent;persist();
 if(r.isError||r.structuredContent.valid!==true||!r.structuredContent.path){
  const details=(r.structuredContent.diagnostics||[]).filter(d=>d.severity==='ERROR').map(d=>`${d.path}: ${d.code}: ${d.message}`);
  throw Error([r.text,...details,'Existing sources and jobs were preserved; repair the named fields and resume.'].join('\n'));
 }
 state.pack=r.structuredContent;persist();
 await call('worldsmith_inspect_world_content',{id:state.pack.id},'saved-content');
}
async function status(){await call(toolNames.has('worldsmith_get_generation_progress')?'worldsmith_get_generation_progress':'worldsmith_get_content_draft',{sessionId:state.sessionId},'generation-progress',{allowError:true});}
async function inspect(){if(!state.pack?.valid||!state.pack?.id)throw Error('No saved pack receipt; repair the last write first');await call('worldsmith_inspect_world_content',{id:state.pack.id},'saved-content');}
async function finish(){const r=await call('worldsmith_finish_world',{sessionId:state.sessionId},'finish-world');state.finish=r.structuredContent;persist();}
async function run(){
 await begin();
 const phases={status,plan,textures,drawings,creatures,previews,modules,architecture,write,inspect,finish};
 const action=phases[options.phase||'drawings'];
 if(!action)throw Error(`Supported phases: ${Object.keys(phases).join(', ')}`);
 return action();
}
run().catch(error=>{console.error(error.stack||String(error));process.exitCode=1;});
