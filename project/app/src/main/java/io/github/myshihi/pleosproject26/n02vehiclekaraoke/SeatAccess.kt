package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One random capability URL per seat; reissued on a new session/process, never a public unscoped control API. */
class SeatAccess {
    private val random = SecureRandom()
    private val tokens = mutableMapOf<SeatId, String>()
    init { renew() }
    fun renew() { SeatId.entries.forEach { tokens[it] = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes)) } }
    fun token(id: SeatId) = tokens.getValue(id)
    fun accepts(id: SeatId, candidate: String?) = candidate != null &&
        MessageDigest.isEqual(token(id).toByteArray(), candidate.toByteArray())
    fun url(host: String, port: Int, seat: SeatId) = "http://$host:$port/join?seat=${seat.name}&token=${token(seat)}"
}

object SeatPage {
    val html = """<!doctype html>
<html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>플레오케 · 내 자리의 무대</title>
<style>
:root{color-scheme:dark;--seat:#7ed8ef;--now:#c4f578}*{box-sizing:border-box}body{margin:0;background:#0b0b18;color:#fff;font-family:system-ui,sans-serif}main{max-width:660px;margin:auto;padding:22px 18px 70px}header{display:flex;justify-content:space-between;align-items:center}h1{color:#c4f578;font-size:24px;letter-spacing:2px}small,.muted{color:#abb0c6}.tag{color:var(--seat);padding:9px 13px;border:1px solid var(--seat);border-radius:20px}.stage{position:relative;text-align:center;border:1px solid #48415e;border-radius:26px;padding:28px 18px;background:radial-gradient(ellipse at top,#322553,transparent 70%),#131326;margin:18px 0;min-height:350px}h2{font-size:20px;margin:5px 0 18px}#cue{color:var(--now);font-weight:700;min-height:28px}#lyric{font-size:clamp(25px,6.5vw,40px);font-weight:900;line-height:1.5;margin:30px 0 20px;color:var(--now);text-shadow:0 0 24px #a27de533}#next{color:#c5c7d7;font-size:17px;min-height:56px}#mine{font-size:22px;font-weight:800;color:var(--seat);margin:25px 0 10px}progress{width:100%;height:6px;accent-color:var(--now)}button,input{font:inherit;border-radius:14px;padding:14px 16px;min-height:48px}button{background:#28253d;color:#fff;border:1px solid #4d4564;cursor:pointer}button:disabled{opacity:.35}button.primary{background:#c4f578;color:#101216;border:none;font-weight:800}section{background:#171727;border:1px solid #2d2d43;border-radius:20px;padding:18px;margin:18px 0}h3{font-size:18px;margin:0 0 14px}.item{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:12px 0}.item span{flex:1}.item small{display:block;margin-top:5px}.notice{padding:12px 0;color:#c4f578;min-height:40px}#join{width:100%}.offline{color:#ffcf87!important}
</style><main>
<header><h1>PLEOKE</h1><span id="seat" class="tag">내 자리</span></header>
<small>차량과 같은 Wi-Fi에 연결해 주세요 · 마이크는 공용 수신기로 연결</small>
<div id="status" class="notice" role="status">차량 무대 연결 중…</div>
<button id="join" class="primary" hidden>이 자리로 참여하기</button>
<div class="stage"><small id="mode">MY KARAOKE SCREEN</small><h2 id="title">오늘의 무대를 기다려요</h2>
<div id="cue">노래와 파트를 준비해 주세요</div><div id="lyric">함께 부를 준비됐나요?</div><div id="next"></div>
<div id="mine"></div><progress id="progress" max="1" value="0"></progress><small id="time"></small></div>
<section><h3>내가 부르고 싶은 파트</h3><small>요청하면 차량 화면에서 파트를 다시 확인해요.</small><div id="parts"></div></section>
<section><h3>내 자리에서 곡 예약</h3><small>시연곡과 차량 화면에서 검색한 구독곡을 예약해요.</small><div id="catalog"></div></section>
<section><h3>우리의 예약 목록</h3><div id="queue"></div></section>
<small>소리는 차량에서 나와요. 휴대폰 마이크를 녹음하거나 전송하지 않아요.<br>구독곡의 노래방 가사·파트는 음악 앱의 별도 제공이 필요해요.</small>
</main><script>
const q=new URLSearchParams(location.search),seat=q.get('seat'),token=q.get('token');
const el=id=>document.getElementById(id);let latest=null,lastStructure='',busy=false,lastGood=0;
const endpoint=path=>path+'?'+new URLSearchParams({seat,token});
async function action(kind,value){if(busy)return;busy=true;try{const r=await fetch(endpoint('/api/action'),{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({kind,value:value||''})});const v=await r.json();el('status').textContent=v.message;lastStructure='';await poll()}catch(e){el('status').textContent='차량 앱 연결을 확인해 주세요'}finally{busy=false}}
function item(text,sub,button,fn,enabled){const row=document.createElement('div');row.className='item';const span=document.createElement('span');span.textContent=text;if(sub){const s=document.createElement('small');s.textContent=sub;span.append(s)}row.append(span);if(button){const b=document.createElement('button');b.textContent=button;b.disabled=!enabled;b.onclick=fn;row.append(b)}return row}
function render(s){latest=s;lastGood=Date.now();el('seat').textContent=s.seatLabel+' · '+s.place;document.documentElement.style.setProperty('--seat',s.seatColor);document.documentElement.style.setProperty('--now',s.color);el('status').className='notice';el('status').textContent=s.allowed?(s.confirmed?'차량과 연결됨 · '+(s.playing?'함께 부르는 중':'재생 대기'):'파트 합의 대기 · 차량 화면에서 확인해 주세요'):'주차·차량 화면 연결을 확인해 주세요';el('join').hidden=s.joined;el('join').disabled=!s.allowed;el('title').textContent=s.title||'오늘의 무대를 기다려요';el('mode').textContent=s.external?'차량 구독 음악 · 가사 미제공':'MY KARAOKE SCREEN';el('cue').textContent=s.cue;el('lyric').textContent=s.lyric;el('next').textContent=s.next;el('mine').textContent=s.mine;el('progress').max=Math.max(s.duration,1);el('progress').value=s.position;el('time').textContent=fmt(s.position)+' / '+(s.duration?fmt(s.duration):'—');const signature=JSON.stringify([s.catalog,s.queue,s.parts,s.allowed,s.joined]);if(signature===lastStructure)return;lastStructure=signature;
el('parts').replaceChildren(...s.parts.map(p=>item(p.title,p.owner,'내가 부르기',()=>action('part',String(p.index)),s.allowed&&s.joined)));
el('catalog').replaceChildren(...s.catalog.map(v=>item(v.title,v.subtitle,'예약',()=>action('reserve',v.id),s.allowed&&s.joined)));
el('queue').replaceChildren(...s.queue.map((v,i)=>item((i===0?'NOW · ':String(i)+' · ')+v.title,v.by,null,null,false)))}
function fmt(ms){let s=Math.floor(Math.max(0,ms)/1000);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')}
async function poll(){try{const r=await fetch(endpoint('/api/state'),{cache:'no-store'});if(!r.ok)throw Error();render(await r.json())}catch(e){if(Date.now()-lastGood>1500){el('status').className='notice offline';el('status').textContent='차량 연결이 끊겼어요. 최신 QR로 다시 연결해 주세요.';document.querySelectorAll('button').forEach(b=>b.disabled=true)}}}
el('join').onclick=()=>action('join');poll();setInterval(poll,600);
</script></html>"""
}
