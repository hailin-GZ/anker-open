(() => {
  'use strict';
  const root=document.documentElement, motionButton=document.querySelector('#motion-toggle');
  const reduce=matchMedia('(prefers-reduced-motion: reduce)');
  let paused=reduce.matches;
  try{paused=paused||localStorage.getItem('shengying-v4-motion')==='paused';}catch{}
  let frame=0,visible=true,phase=0,lastTime=0;
  const canvas=document.querySelector('#sound-field'),ctx=canvas.getContext('2d');
  const stage=document.querySelector('.device-stage');
  let width=1,height=1,dpr=1,pointerX=0,pointerY=0;
  function resize(){const box=stage.getBoundingClientRect();width=box.width;height=box.height;dpr=Math.min(devicePixelRatio||1,1.75);canvas.width=Math.round(width*dpr);canvas.height=Math.round(height*dpr);ctx?.setTransform(dpr,0,0,dpr,0,0);draw();}
  function draw(){
    if(!ctx)return;
    ctx.clearRect(0,0,width,height);
    const cx=width*.52,cy=height*.52,unit=Math.min(width,height);
    const halo=ctx.createRadialGradient(cx,cy,0,cx,cy,unit*.57);
    halo.addColorStop(0,'#6895b825');halo.addColorStop(.55,'#aac7da18');halo.addColorStop(1,'#f4f7fa00');ctx.fillStyle=halo;ctx.fillRect(0,0,width,height);
    ctx.save();ctx.translate(cx+pointerX*4,cy+pointerY*3);ctx.rotate(-.32);
    for(let j=0;j<62;j++){
      const f=j/61,tilt=f*2-1;ctx.beginPath();
      for(let i=0;i<=125;i++){
        const t=i/125*Math.PI*2,r=unit*(.23+f*.24);
        const wave=Math.sin(t*4+phase+f*7)*unit*.012+Math.cos(t*7-phase*.7)*unit*.004;
        const x=Math.cos(t)*(r+wave),y=Math.sin(t)*(r*.47+wave)+tilt*unit*.11;
        if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);
      }
      ctx.strokeStyle=`rgba(${45+Math.round(f*35)},${105+Math.round(f*40)},180,${.07+f*.12})`;ctx.lineWidth=.7;ctx.stroke();
    }
    for(let i=0;i<76;i++){
      const t=i*2.39996+phase*.035,r=unit*(.29+(i%13)/65),x=Math.cos(t)*r,y=Math.sin(t)*r*.57;
      ctx.beginPath();ctx.arc(x,y,i%9===0?1.4:.65,0,Math.PI*2);ctx.fillStyle=i%9===0?'#3d7da7a0':'#6190b362';ctx.fill();
    }ctx.restore();
  }
  function animate(time){frame=0;if(paused||reduce.matches||document.hidden||!visible)return;if(time-lastTime>32){phase+=.011;draw();lastTime=time;}frame=requestAnimationFrame(animate);}
  function schedule(){cancelAnimationFrame(frame);frame=0;if(!paused&&!reduce.matches&&!document.hidden&&visible)frame=requestAnimationFrame(animate);else draw();}
  function setMotion(){root.classList.toggle('motion-paused',paused);motionButton.setAttribute('aria-pressed',String(paused));motionButton.querySelector('span').textContent=paused?'开启动效':'暂停动效';motionButton.setAttribute('aria-label',paused?'开启动效':'暂停动效');schedule();}
  motionButton.addEventListener('click',()=>{paused=!paused;try{localStorage.setItem('shengying-v4-motion',paused?'paused':'on');}catch{}setMotion();});
  reduce.addEventListener('change',()=>{paused=reduce.matches;setMotion();});document.addEventListener('visibilitychange',schedule);
  const visibilityObserver=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;schedule();});visibilityObserver.observe(stage);
  const mobileBar=document.querySelector('.mobile-download-bar');
  new IntersectionObserver(entries=>{const hidden=entries[0].isIntersecting;mobileBar.classList.toggle('at-download',hidden);mobileBar.inert=hidden;},{threshold:0.15}).observe(document.querySelector('#download'));
  const sizeObserver=new ResizeObserver(resize);sizeObserver.observe(stage);
  stage.addEventListener('pointermove',event=>{if(paused||reduce.matches||event.pointerType==='touch')return;const b=stage.getBoundingClientRect();pointerX=(event.clientX-b.left)/b.width-.5;pointerY=(event.clientY-b.top)/b.height-.5;stage.style.setProperty('--px',`${pointerX*12}px`);stage.style.setProperty('--py',`${pointerY*10}px`);});
  stage.addEventListener('pointerleave',()=>{pointerX=pointerY=0;stage.style.setProperty('--px','0px');stage.style.setProperty('--py','0px');});
  const checksum='535e9fc7d791cc92d4018b7d0bfc8ba7d0ae1391528f1ee5b6eb32d2601838b0';
  document.querySelector('#copy-checksum').addEventListener('click',async()=>{const status=document.querySelector('#copy-status');try{await navigator.clipboard.writeText(checksum);status.textContent='已复制 SHA-256，可用于核对安装包。';}catch{status.textContent=`无法自动复制，请手动复制：${checksum}`;}});
  document.querySelector('#apk-download').addEventListener('click',()=>{document.querySelector('#copy-status').textContent='已请求下载安装包。如未弹出提示，请检查浏览器的下载列表。';});
  const destination=window.SHENGYING_EXPERIENCE?.url;
  if(destination){try{const target=new URL(destination,location.href);if(['http:','https:'].includes(target.protocol))document.querySelectorAll('.online-entry').forEach(link=>link.href=target.href);}catch{}}
  setMotion();resize();
})();
