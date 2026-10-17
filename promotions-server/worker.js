/*
 * CFG Riff Rumble Promotions API (staged, OFF by default).
 *
 * Deployment requires OWNER-managed Shopify + Cloudflare credentials.
 * Shopify verification/sync must populate KV with freshly verified country feeds.
 * Nothing in this worker may self-approve an unverified promotion.
 *
 * env.PROMOTION_CACHE : KV (enabled = 'yes' only after a signed Shopify sync)
 * env.DB              : Cloudflare D1
 * env.OWNER_TOKEN     : random secret for private metrics/preview endpoints
 * env.ALERT_ENDPOINT  : optional owner-configured HTTPS notification receiver
 */
const json=(value,status=200)=>new Response(JSON.stringify(value),{
 status,headers:{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
});
const disabled=(country="")=>json({enabled:false,country,offers:[]});
const iso=s=>/^[A-Z]{2}$/.test(s||"");
const safeStore=url=>{try{const u=new URL(url);return u.protocol==="https:"&&
 (u.hostname==="cablefreeguitar.com"||u.hostname==="www.cablefreeguitar.com");}
 catch{return false;}};
function recordValid(p,country,now){
 if(!p||!p.approved||!p.verified||!p.inStock||p.country!==country||
   !p.promotionId||!p.productId||!p.headline||!p.formattedPrice||!safeStore(p.clickUrl))return false;
 return Number.isFinite(p.verifiedUntilMs)&&p.verifiedUntilMs>now&&p.verifiedUntilMs<=now+15*60*1000;
}
async function enabled(env){return env.PROMOTION_CACHE&&await env.PROMOTION_CACHE.get("enabled")==="yes";}
async function feedFor(env,country){
 if(!await enabled(env)||!iso(country))return null;
 const cached=await env.PROMOTION_CACHE.get("live:"+country,"json");
 if(!cached||!Array.isArray(cached.offers)||Date.now()-Number(cached.updatedAt)>5*60*1000)return null;
 const offers=cached.offers.filter(p=>recordValid(p,country,Date.now()));
 return {enabled:true,country,offers};
}
function allowedOwner(req,env) {
 const auth=req.headers.get("Authorization")||"";
 return !!env.OWNER_TOKEN&&env.OWNER_TOKEN.length>=32&&auth===("Bearer "+env.OWNER_TOKEN);
}
async function events(req,env){
 if(!env.DB)return json({error:"Analytics not connected"},503);
 const size=Number(req.headers.get("Content-Length")||"0");
 if(size>2048)return json({error:"Bad body"},400);
 let data;try{data=await req.json();}catch{return json({error:"Bad JSON"},400);}
 const {type,promotionId,placement,country}=data||{};
 if(!["click","impression"].includes(type)||!["home","results"].includes(placement)||
   !iso(country)||typeof promotionId!=="string"||!/^[a-zA-Z0-9_.-]{1,120}$/.test(promotionId))return json({error:"Invalid event"},400);
 const feed=await feedFor(env,country);
 if(!feed||!feed.offers.some(x=>x.promotionId===promotionId))return json({error:"Inactive campaign"},409);
 await env.DB.prepare("INSERT INTO promo_events (event_type,promotion_id,placement,country) VALUES (?,?,?,?)")
 .bind(type,promotionId,placement,country).run();
 return new Response(null,{status:204,headers:{"Cache-Control":"no-store"}});
}
async function metrics(env){
 const result=await env.DB.prepare(
  "SELECT promotion_id, placement, SUM(CASE WHEN event_type='impression' THEN 1 ELSE 0 END) AS impressions, "+
  "SUM(CASE WHEN event_type='click' THEN 1 ELSE 0 END) AS clicks "+
  "FROM promo_events WHERE recorded_at >= datetime('now','-7 days') GROUP BY promotion_id, placement ORDER BY impressions DESC"
 ).all();
 return (result.results||[]).map(x=>{
  const n=Number(x.impressions||0), clicks=Number(x.clicks||0);
  return {...x,ctr_pct:n?Math.round(10000*clicks/n)/100:null, clicks_per_1000_impressions:n?Math.round(10000*clicks/n)/10:null};
 });
}
async function alerts(env,subject,body){
 if(!env.ALERT_ENDPOINT||!env.ALERT_AUTH_TOKEN)return false;
 const url=new URL(env.ALERT_ENDPOINT);
 if(url.protocol!=="https:")return false;
 const res=await fetch(url.toString(),{method:"POST",
  headers:{"Content-Type":"application/json","Authorization":"Bearer "+env.ALERT_AUTH_TOKEN},
  body:JSON.stringify({to:"ariel@cablefreeguitar.com",subject,body})});
 return res.ok;
}
async function checkStale(env){
 if(!env.PROMOTION_CACHE)return;
 // No Shopify API scopes or verification service yet. Strictly reject stale cached offers.
 const page=await env.PROMOTION_CACHE.list({prefix:"live:"});
 for(const entry of page.keys){
  const v=await env.PROMOTION_CACHE.get(entry.name,"json");
  if(!v||!Array.isArray(v.offers))continue;
  const active=v.offers.filter(p=>recordValid(p,entry.name.slice(5),Date.now()));
  if(active.length===v.offers.length)continue;
  const removed=v.offers.filter(p=>!active.some(x=>x.promotionId===p.promotionId));
  await env.PROMOTION_CACHE.put(entry.name,JSON.stringify({updatedAt:Date.now(),offers:active}));
  for(const p of removed){
   if(!env.DB)continue;
   const id=String(p.promotionId||"unknown").slice(0,120),reason="invalid_or_stale_verification";
   const old=await env.DB.prepare("SELECT active FROM promo_incidents WHERE campaign_id=? AND reason=?").bind(id,reason).first();
   if(!old||old.active!==1){
    await env.DB.prepare("INSERT INTO promo_incidents(campaign_id,reason,active) VALUES(?,?,1) ON CONFLICT(campaign_id,reason) DO UPDATE SET active=1, last_seen=datetime('now')").bind(id,reason).run();
    await alerts(env,"CFG Riff Rumble: promotion hidden",id+" was hidden: verification expired or product/discount no longer valid. Check Shopify.");
   }
  }
 }
}
async function weekly(env){
 if(!env.DB)return;
 const local=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Jerusalem",weekday:"short",hour:"2-digit",hourCycle:"h23"}).format(new Date());
 if(!local.includes("Mon")||!local.includes("09"))return;
 const day=new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Jerusalem",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
 const key="digest:"+day;
 if(await env.PROMOTION_CACHE.get(key))return;
 const rows=await metrics(env);
 const txt=rows.map(r=>r.promotion_id+" ("+r.placement+"): "+r.impressions+" views, "+r.clicks+
    " clicks, "+(r.ctr_pct??"N/A")+"% CTR; "+(r.clicks_per_1000_impressions??"N/A")+
    " clicks/1000 impressions").join("\n");
 const note="\n\nSales and revenue not included until verified Shopify order attribution is connected. Recommendations require enough data and owner approval.";
 if(await alerts(env,"CFG Riff Rumble weekly promotions report",txt+note))
   await env.PROMOTION_CACHE.put(key,"sent",{expirationTtl:60*60*24*10});
}
export default {
 async fetch(req,env) {
  const url=new URL(req.url);
  try{
   if(url.pathname==="/health")return json({ok:true,adsEnabled:await enabled(env)});
   if(url.pathname==="/api/promotions/v1"&&req.method==="GET"){
    const raw=(url.searchParams.get("country")||"AUTO").toUpperCase();
    const country=raw==="AUTO"?String(req.cf?.country||"").toUpperCase():raw;
    const feed=await feedFor(env,country);
    return feed?json({...feed,metricsUrl:url.origin+"/api/promotions/events"}):disabled(country);
   }
   if(url.pathname==="/api/promotions/events"&&req.method==="POST")return events(req,env);
   if(url.pathname==="/owner/metrics"&&req.method==="GET"){
    if(!allowedOwner(req,env))return json({error:"Unauthorized"},401);
    return json({metrics:await metrics(env)});
   }
   if(url.pathname==="/owner/test-feed"&&req.method==="GET"){
    if(!allowedOwner(req,env))return json({error:"Unauthorized"},401);
    const country=(url.searchParams.get("country")||"US").toUpperCase();
    // Draft preview is NEVER returned from the public endpoint.
    return json({preview:true,feed:await env.PROMOTION_CACHE.get("draft:"+country,"json")});
   }
   return json({error:"Not found"},404);
  }catch(e){
   console.error("promo failure",e?.message);
   return disabled();
  }
 },
 async scheduled(event,env,ctx){
  ctx.waitUntil((async()=>{
   if(event.cron==="*/5 * * * *")await checkStale(env);
   else await weekly(env);
  })());
 }
};
