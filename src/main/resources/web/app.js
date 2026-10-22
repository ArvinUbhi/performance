'use strict';
const el=id=>document.getElementById(id);
const state={pid:null,start:null,range:3600000,metric:'cpuPercent',samples:[],snapshots:[],watched:[],status:{}};
const fmt={
  pct:x=>Number.isFinite(x)?x.toFixed(1)+'%':'—',
  num:x=>Number.isFinite(x)?x.toLocaleString():'—',
  mem:x=>x==null||x<0?'—':x>=1073741824?(x/1073741824).toFixed(2)+' GB':(x/1048576).toFixed(1)+' MB',
  time:x=>new Date(x).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}),
  date:x=>new Date(x).toLocaleString()
};
async function api(path,method='GET'){
  const response=await fetch(path,{method,headers:{'Accept':'application/json'}});
  const data=await response.json();
  if(!response.ok)throw Error(data.error||`Request failed (${response.status})`);
  return data;
}
function message(text,error=false){el('message').hidden=false;el('message').classList.toggle('error',error);el('message').textContent=text;}
function clearMessage(){el('message').hidden=true;}
function query(params){return new URLSearchParams(params).toString();}
function selectPid(pid){state.pid=pid||null;let item=state.watched.find(x=>String(x.pid)===String(pid));state.start=item?.sample?.startMillis||null;el('process-select').value=pid||'';refreshSelected();}
async function refreshAll(){
  try {
    const [result,status]=await Promise.all([api('/api/processes'),api('/api/status')]);
    state.watched=result.watched;state.status=status;
    el('engine-status').textContent=`${status.collector} · ${status.lastCycleMillis ?? 0} ms cycle · ${status.samplingBudgetSkips ?? 0} skips · ${status.droppedHistorySamples} drops`;
    const values=state.watched.map(x=>String(x.pid));
    if(state.pid&&!values.includes(String(state.pid)))state.pid=null;
    if(!state.pid&&values.length)state.pid=values[0];
    const select=el('process-select');select.replaceChildren();
    if(!values.length){const o=document.createElement('option');o.value='';o.textContent='No watched processes';select.append(o);}
    for(const row of state.watched){const o=document.createElement('option');o.value=row.pid;o.textContent=`${row.sample?.name||'Waiting'} · PID ${row.pid}`;select.append(o);}
    select.value=state.pid||'';
    el('watch-count').textContent=`${values.length} process${values.length===1?'':'es'}`;
    const list=el('process-list');list.replaceChildren();
    if(!values.length){const d=document.createElement('div');d.className='empty';d.textContent='No watched processes yet.';list.append(d);}
    for(const row of state.watched){
      const item=document.createElement('div');item.className='process-item';
      const info=document.createElement('div');const title=document.createElement('strong');title.textContent=row.sample?.name||'Waiting for sample';
      const sub=document.createElement('small');sub.textContent=`PID ${row.pid} · ${row.sample?.state||'Unobserved'} · ${fmt.mem(row.sample?.rssBytes)}`;
      info.append(title,sub);const actions=document.createElement('div');actions.className='process-actions';
      const open=document.createElement('button');open.className='button ghost';open.textContent='Inspect';open.onclick=()=>selectPid(String(row.pid));
      const remove=document.createElement('button');remove.className='button ghost';remove.textContent='Remove';remove.onclick=async()=>{try{await api('/api/watch?'+query({pid:row.pid}),'DELETE');await refreshAll();}catch(err){message(err.message,true);}};
      actions.append(open,remove);item.append(info,actions);list.append(item);
    }
    const selected=state.watched.find(x=>String(x.pid)===String(state.pid))?.sample;
    if(selected){
      if(state.start!==selected.startMillis){state.start=selected.startMillis;await reloadSnapshots();}
      el('cpu').textContent=fmt.pct(selected.cpuPercent);el('rss').textContent=fmt.mem(selected.rssBytes);
      el('threads').textContent=fmt.num(selected.threads);el('load').textContent=Number.isFinite(selected.loadAverage)?selected.loadAverage.toFixed(2):'—';
      el('cpu-sub').textContent=`Host CPU: ${fmt.pct(selected.hostCpuPercent)}`;
      el('threads-sub').textContent=`${selected.state} · PID ${selected.pid}`;
      el('load-sub').textContent=`${selected.logicalCores} logical CPUs`;
      renderInsights(selected);
    } else {
      ['cpu','rss','threads','load'].forEach(id=>el(id).textContent='—');
      el('insights').textContent='Select a running PID for diagnostic signals.';
    }
    await reloadHistory();
    clearMessage();
  }catch(err){message(err.message,true);}
}
async function refreshSelected(){await reloadSnapshots();await reloadHistory();refreshAll();}
async function reloadHistory(){
  if(!state.pid||!state.start){state.samples=[];drawChart();return;}
  const now=Date.now();
  try {const result=await api('/api/history?'+query({pid:state.pid,startMillis:state.start,from:now-state.range,to:now,max:550}));
    state.samples=result.samples.map((s,i,all)=>{
      if(!i)return {...s,readRateBytes:NaN,writeRateBytes:NaN};
      const previous=all[i-1],elapsed=(s.timestamp-previous.timestamp)/1000;
      const rate=(current,prior)=>elapsed>0&&current>=0&&prior>=0&&current>=prior?(current-prior)/elapsed:NaN;
      return {...s,readRateBytes:rate(s.readBytes,previous.readBytes),writeRateBytes:rate(s.writeBytes,previous.writeBytes)};
    });drawChart();}
  catch(err){message(err.message,true);}
}
const metrics={cpuPercent:{label:'CPU usage',unit:'%',format:fmt.pct},rssBytes:{label:'Resident memory',unit:'',format:fmt.mem},threads:{label:'Threads',unit:'',format:fmt.num},readRateBytes:{label:'Read throughput',unit:'bytes/s',format:x=>fmt.mem(x)+'/s'},writeRateBytes:{label:'Write throughput',unit:'bytes/s',format:x=>fmt.mem(x)+'/s'},hostCpuPercent:{label:'Host CPU usage',unit:'%',format:fmt.pct}};
function drawChart(){
  const chart=el('chart'),wrap=chart.parentElement,rect=wrap.getBoundingClientRect(),ratio=window.devicePixelRatio||1;
  const width=Math.max(rect.width,260),height=Math.max(rect.height,180);
  chart.width=Math.floor(width*ratio);chart.height=Math.floor(height*ratio);
  const ctx=chart.getContext('2d');ctx.scale(ratio,ratio);ctx.clearRect(0,0,width,height);
  const pad={l:53,r:15,t:20,b:28},cw=width-pad.l-pad.r,ch=height-pad.t-pad.b;
  const rows=state.samples.filter(s=>Number.isFinite(s[state.metric])&&s[state.metric]>=0);
  el('chart-empty').hidden=rows.length>0;
  if(!rows.length){el('chart-empty').textContent='No samples yet for this process and time range.';el('chart-summary').textContent='No samples';return;}
  let min=Math.min(...rows.map(s=>s[state.metric])),max=Math.max(...rows.map(s=>s[state.metric]));
  if(['cpuPercent','hostCpuPercent'].includes(state.metric)){min=0;max=Math.max(max*1.12,100);}
  else {min=Math.max(0,min-(max-min)*.2);max=Math.max(max+Math.max(max-min,1)*.15,1);}
  const start=Math.min(...rows.map(s=>s.timestamp)),end=Math.max(...rows.map(s=>s.timestamp));
  const x=s=>pad.l+(end===start?cw/2:(s.timestamp-start)/(end-start)*cw);
  const y=s=>pad.t+ch-(s[state.metric]-min)/(max-min)*ch;
  ctx.font='11px system-ui';ctx.textAlign='right';ctx.strokeStyle='#293a4c';ctx.fillStyle='#8397aa';
  for(let i=0;i<=4;i++){const v=min+(max-min)*(4-i)/4,yy=pad.t+ch*i/4;ctx.beginPath();ctx.moveTo(pad.l,yy);ctx.lineTo(width-pad.r,yy);ctx.stroke();
    ctx.fillText(metrics[state.metric].format(v),pad.l-9,yy+3);}
  ctx.beginPath();ctx.moveTo(x(rows[0]),pad.t+ch);
  rows.forEach(s=>ctx.lineTo(x(s),y(s)));ctx.lineTo(x(rows[rows.length-1]),pad.t+ch);ctx.closePath();
  const gradient=ctx.createLinearGradient(0,pad.t,0,pad.t+ch);gradient.addColorStop(0,'rgba(90,214,192,.28)');gradient.addColorStop(1,'rgba(90,214,192,0)');ctx.fillStyle=gradient;ctx.fill();
  ctx.beginPath();rows.forEach((s,i)=>i?ctx.lineTo(x(s),y(s)):ctx.moveTo(x(s),y(s)));
  ctx.lineWidth=2.3;ctx.strokeStyle='#5ad6c0';ctx.stroke();
  ctx.beginPath();ctx.arc(x(rows[rows.length-1]),y(rows[rows.length-1]),3.5,0,Math.PI*2);ctx.fillStyle='#5ad6c0';ctx.fill();
  el('chart-from').textContent=fmt.time(start);el('chart-to').textContent=fmt.time(end);
  el('chart-summary').textContent=`${rows.length} points · Latest ${metrics[state.metric].format(rows[rows.length-1][state.metric])}`;
}
async function reloadSnapshots(){
  if(!state.pid){state.snapshots=[];populateSnapshotOptions();return;}
  try{const result=await api('/api/snapshots?'+query({pid:state.pid}));state.snapshots=result.snapshots;populateSnapshotOptions();}
  catch(err){message(err.message,true);}
}
function populateSnapshotOptions(){
  for(const id of ['snapshot-a','snapshot-b']){
    const select=el(id),old=select.value;select.replaceChildren();
    const empty=document.createElement('option');empty.value='';empty.textContent='Select snapshot';select.append(empty);
    state.snapshots.forEach(s=>{const opt=document.createElement('option');opt.value=s.id;opt.textContent=`${s.label} · ${fmt.date(s.capturedAt)}`;select.append(opt);});
    if(state.snapshots.some(s=>s.id===old))select.value=old;
  }
  compareSnapshots();
}
function compareSnapshots(){
  const a=state.snapshots.find(s=>s.id===el('snapshot-a').value),b=state.snapshots.find(s=>s.id===el('snapshot-b').value);
  const root=el('comparison');root.replaceChildren();
  if(!a||!b){root.textContent='Choose a baseline and comparison snapshot.';return;}
  const line=(name,diff,formatter,positiveBad)=>{
    const div=document.createElement('div');div.className='comparison-row';const label=document.createElement('span');label.textContent=name;
    const val=document.createElement('strong');val.textContent=(diff>0?'+':diff<0?'−':'')+formatter(Math.abs(diff));
    if(diff!==0)val.className=(diff>0)===positiveBad?'up':'down';div.append(label,val);root.append(div);
  };
  line('Process CPU',b.sample.cpuPercent-a.sample.cpuPercent,x=>fmt.pct(x),true);
  line('Resident memory',b.sample.rssBytes-a.sample.rssBytes,fmt.mem,true);
  line('Threads',b.sample.threads-a.sample.threads,fmt.num,true);
  line('Host CPU',b.sample.hostCpuPercent-a.sample.hostCpuPercent,x=>fmt.pct(x),true);
  line('System load',b.sample.loadAverage-a.sample.loadAverage,x=>x.toFixed(2),true);
  if(a.sample.startMillis!==b.sample.startMillis){const p=document.createElement('p');p.textContent='Different process executions: compare with care.';root.append(p);}
}
function renderInsights(s){
  const signals=[];
  if(Number.isFinite(s.cpuPercent)&&s.cpuPercent>100)signals.push(['MULTICORE CPU',`Using ${fmt.pct(s.cpuPercent)} of one core; this process is actively consuming multiple cores.`]);
  if(Number.isFinite(s.hostCpuPercent)&&s.hostCpuPercent>88&&s.loadAverage>s.logicalCores)signals.push(['HOST CONTENTION',`Host CPU is ${fmt.pct(s.hostCpuPercent)} and 1-minute load exceeds logical cores. Check run-queue contention before blaming the application.`]);
  if(s.threads>300)signals.push(['THREAD FOOTPRINT',`${s.threads} threads reported. Compare with a healthy baseline to investigate thread growth.`]);
  if(s.rssBytes>4294967296)signals.push(['MEMORY FOOTPRINT',`${fmt.mem(s.rssBytes)} resident memory. Consider capacity and expected workload size.`]);
  if(!signals.length)signals.push(['NO RULE TRIGGERED','No simple threshold signals are active. This does not guarantee application health.']);
  const root=el('insights');root.replaceChildren();
  signals.forEach(([title,body])=>{const box=document.createElement('div');box.className='insight';const t=document.createElement('b');t.textContent=title;box.append(t,document.createTextNode(body));root.append(box);});
}
el('watch-form').addEventListener('submit',async e=>{e.preventDefault();try{const pid=el('watch-pid').value;await api('/api/watch?'+query({pid}),'POST');el('watch-pid').value='';state.pid=String(pid);await refreshAll();message(`Watching PID ${pid}. First CPU delta will be available after the next sample.`);}catch(err){message(err.message,true);}});
el('process-select').onchange=e=>selectPid(e.target.value);
el('refresh').onclick=refreshAll;
el('range').addEventListener('click',e=>{const b=e.target.closest('[data-range]');if(!b)return;state.range=Number(b.dataset.range);el('range').querySelectorAll('button').forEach(x=>x.classList.toggle('selected',x===b));reloadHistory();});
el('metrics').addEventListener('click',e=>{const b=e.target.closest('[data-metric]');if(!b)return;state.metric=b.dataset.metric;el('metrics').querySelectorAll('button').forEach(x=>x.classList.toggle('selected',x===b));drawChart();});
// Asynchronous capture: the HTTP request only queues work; polling never blocks daemon threads.
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
el('capture').onclick=async()=>{
  const button=el('capture');
  if(button.disabled)return;
  button.disabled=true;
  try{
    if(!state.pid)throw Error('Select a process first');
    const label=el('snapshot-label').value.trim()||'Quick snapshot';
    const queued=await api('/api/snapshots?'+query({pid:state.pid,label}),'POST');
    message('Snapshot queued on the dedicated worker pool…');
    let result=queued;
    for(let attempt=0;attempt<120 && result.status!=='SUCCEEDED' && result.status!=='FAILED';attempt++){
      await sleep(250);
      result=await api('/api/snapshot-jobs?'+query({id:queued.jobId}));
    }
    if(result.status==='FAILED')throw Error(result.error||'Snapshot failed');
    if(result.status!=='SUCCEEDED')throw Error('Snapshot is still running. You can check its job ID: '+queued.jobId);
    const snapshot=result.snapshot;
    el('snapshot-label').value='';
    await reloadSnapshots();
    el('snapshot-b').value=snapshot.id;
    compareSnapshots();
    message('Snapshot captured successfully.');
  }catch(err){message(err.message,true);}
  finally{button.disabled=false;}
};
el('snapshot-a').onchange=compareSnapshots;el('snapshot-b').onchange=compareSnapshots;
el('diag-run').onclick=async()=>{try{if(!state.pid)throw Error('Select a process first');const out=el('diag-output');out.textContent='Collecting requested diagnostic…';const r=await api('/api/diagnostics?'+query({pid:state.pid,kind:el('diag-kind').value}),'POST');out.textContent=r.output;}catch(err){el('diag-output').textContent=err.message;message(err.message,true);}};
el('export').onclick=()=>{if(!state.samples.length)return message('No history to export.',true);const content=JSON.stringify({pid:state.pid,points:state.samples},null,2);const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([content],{type:'application/json'}));a.download=`quantlab-pid-${state.pid}-${Date.now()}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),250);};
window.addEventListener('resize',drawChart);
refreshAll().then(reloadSnapshots);
setInterval(refreshAll,15000);
