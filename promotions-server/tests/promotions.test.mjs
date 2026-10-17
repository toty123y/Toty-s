import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../worker.js';

class KV {
 constructor(){this.values=new Map();}
 async get(key,mode){const v=this.values.get(key);if(v===undefined)return null;return mode==='json'?JSON.parse(v):v;}
 async put(k,v){this.values.set(k,v);}
 async list({prefix}){return {keys:[...this.values.keys()].filter(k=>k.startsWith(prefix)).map(name=>({name}))};}
}
function make(){
 const PROMOTION_CACHE=new KV();
 return {PROMOTION_CACHE,DB:null,OWNER_TOKEN:'a'.repeat(48)};
}
const request=(path,opts)=>new Request('https://test.workers.dev'+path,opts);
const offer=(country='US')=>({
 promotionId:'demo-verified',productId:'product-1',approved:true,verified:true,inStock:true,
 country,priority:'Normal',headline:'Sample verified promotion',subtitle:'Test only',
 cta:'SHOP THE DEAL',formattedPrice:'$25.00',imageUrl:'',
 code:'',clickUrl:'https://www.cablefreeguitar.com/products/deadon-clip-on-tuner',
 verifiedUntilMs:Date.now()+3*60*1000
});
test('global kill switch defaults OFF',async()=>{
 const env=make();let res=await worker.fetch(request('/api/promotions/v1?country=US'),env);
 assert.equal(res.status,200);const body=await res.json();
 assert.equal(body.enabled,false);assert.deepEqual(body.offers,[]);
});
test('valid verified offers are served only when flag ON',async()=>{
 const env=make();
 await env.PROMOTION_CACHE.put('enabled','yes');
 await env.PROMOTION_CACHE.put('live:US',JSON.stringify({updatedAt:Date.now(),offers:[offer()]}));
 const res=await worker.fetch(request('/api/promotions/v1?country=US'),env);
 const body=await res.json();
 assert.equal(body.enabled,true);assert.equal(body.offers.length,1);assert.equal(body.offers[0].country,'US');
});
test('stale discount verification fails closed',async()=>{
 const env=make();await env.PROMOTION_CACHE.put('enabled','yes');
 const invalid=offer();invalid.verifiedUntilMs=Date.now()-100;
 await env.PROMOTION_CACHE.put('live:US',JSON.stringify({updatedAt:Date.now(),offers:[invalid]}));
 const body=await (await worker.fetch(request('/api/promotions/v1?country=US'),env)).json();
 assert.equal(body.offers.length,0);
});
test('unverified or off-domain offer never surfaces',async()=>{
 const env=make();await env.PROMOTION_CACHE.put('enabled','yes');
 const bad=offer();bad.clickUrl='https://fake-promotions.example/steal';
 const fake=offer();fake.verified=false;
 await env.PROMOTION_CACHE.put('live:US',JSON.stringify({updatedAt:Date.now(),offers:[bad,fake]}));
 const body=await (await worker.fetch(request('/api/promotions/v1?country=US'),env)).json();
 assert.equal(body.offers.length,0);
});
test('private QA preview denies anonymous requests',async()=>{
 const env=make();
 const res=await worker.fetch(request('/owner/test-feed?country=US'),env);
 assert.equal(res.status,401);
});
