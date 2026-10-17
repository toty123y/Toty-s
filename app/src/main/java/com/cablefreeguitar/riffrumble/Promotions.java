package com.cablefreeguitar.riffrumble;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Fail-closed promo client. No offers are shipped in the APK.
 * A public config selects a trusted promotion backend; only verified live offers render.
 * Shopify secrets, eligibility/discount/cart logic and the global kill switch live server-side.
 */
public final class Promotions {
  private static final String CONFIG_URL =
    "https://raw.githubusercontent.com/toty123y/Toty-s/cfg-riff-shopify-promotions/config/promotions.json";
  private static final String STORE_HOST="cablefreeguitar.com";
  private final MainActivity app;
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final Handler ui=new Handler(Looper.getMainLooper());
  private final Random random=new Random();
  private final ArrayList<Offer> items=new ArrayList<>();
  private final Set<String> impressions=new HashSet<>();
  private String feedUrl="",metricsUrl="",homeId="",resultId="";
  private boolean dismissed=false,enabled=false,loading=false,closed=false;
  private long fetchedAt=0;
  private int revision=0,gameId=0;

  Promotions(MainActivity activity){app=activity;}
  void close(){closed=true;revision++;io.shutdownNow();}
  boolean isDismissed(){return dismissed;}
  void dismiss(){dismissed=true;app.renderSafe();}
  void newGame(){gameId++;homeId="";resultId="";impressions.clear();choose();}
  void changeRegion(String region) {
    app.prefs.edit().putString("promo_region",region).apply();
    refresh();
  }
  String region(){return app.prefs.getString("promo_region","");}
  String regionLabel(){
    String r=region();return r.isEmpty()?"Automatic":new Locale("",r).getDisplayCountry(Locale.ENGLISH);
  }
  void choose(){
    homeId="";resultId="";
    if(!enabled || items.size()==0)return;
    if(items.size()==1){resultId=items.get(0).id;return;}
    Offer home=pick(items);
    if(home==null)return;homeId=home.id;
    ArrayList<Offer> others=new ArrayList<>();
    for(Offer item:items)if(!item.productId.equals(home.productId))others.add(item);
    Offer result=pick(others);
    if(result==null){homeId="";resultId=home.id;}
    else resultId=result.id;
  }
  Offer pick(ArrayList<Offer> candidates){
    if(candidates.isEmpty())return null;
    int sum=0;for(Offer p:candidates)sum+=p.weight;
    int roll=random.nextInt(sum);
    for(Offer p:candidates){roll-=p.weight;if(roll<0)return p;}
    return candidates.get(0);
  }
  Offer find(String id){
    for(Offer item:items)if(item.id.equals(id))return item;
    return null;
  }
  void refresh(){
    if(loading||closed)return;
    // Hide existing ads until Shopify's backend confirms current validity.
    enabled=false;items.clear();homeId="";resultId="";
    loading=true;final int run=++revision;
    if(app.screen.equals("home")||app.screen.equals("finished"))app.renderSafe();
    io.execute(()->{
      ArrayList<Offer> fresh=new ArrayList<>();
      String feed="",metric="";boolean ok=false;
      try{
        JSONObject conf=readJson(CONFIG_URL);
        if(conf.optBoolean("enabled",false)){
          String proposed=conf.optString("feedUrl","");
          if(trustedApi(proposed)){
            feed=proposed;
            String requested=region();
            String endpoint=feed+(feed.contains("?")?"&":"?")+"country="+(requested.isEmpty()?"AUTO":requested);
            JSONObject response=readJson(endpoint);
            boolean active=response.optBoolean("enabled",false);
            String country=response.optString("country","");
            String metricEndpoint=response.optString("metricsUrl","");
            // A requested country MUST be honored by the backend.
            if(active&&country.matches("[A-Z]{2}")&&(requested.isEmpty()||requested.equals(country))){
              JSONArray ads=response.optJSONArray("offers");
              if(ads!=null){
                for(int i=0;i<Math.min(ads.length(),30);i++){
                  Offer item=Offer.parse(ads.optJSONObject(i),country);
                  if(item!=null)fresh.add(item);
                }
                if(trustedApi(metricEndpoint))metric=metricEndpoint;
                ok=true;
              }
            }
          }
        }
      }catch(Exception ignored){ /* Fail closed: no stale prices or expired deals. */ }
      final boolean valid=ok;final String target=feed,measure=metric;
      ui.post(()->{
        if(closed||run!=revision||app.isFinishing())return;
        loading=false;enabled=valid;feedUrl=target;metricsUrl=measure;fetchedAt=System.currentTimeMillis();
        items.clear();if(valid)items.addAll(fresh);choose();
        if(app.screen.equals("home")||app.screen.equals("finished"))app.renderSafe();
      });
    });
  }
  private static boolean trustedApi(String value){
    try{
      Uri u=Uri.parse(value);
      if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getEncodedAuthority()==null||u.getEncodedAuthority().contains("@"))return false;
      String host=u.getHost().toLowerCase(Locale.US);
      return host.equals("cablefreeguitar.com")||host.equals("www.cablefreeguitar.com")||
             host.endsWith(".workers.dev")||host.equals("cfg-riff-rumble.gonzaley.chatgpt.site");
    }catch(Exception e){return false;}
  }
  private static boolean validShopLink(String value){
    try{
      Uri u=Uri.parse(value);
      String host=u.getHost();return "https".equalsIgnoreCase(u.getScheme())&&host!=null&&
        (host.equalsIgnoreCase(STORE_HOST)||host.equalsIgnoreCase("www."+STORE_HOST));
    }catch(Exception ignored){return false;}
  }
  static JSONObject readJson(String address)throws IOException,JSONException {
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setRequestMethod("GET");c.setConnectTimeout(7000);c.setReadTimeout(7000);
    c.setRequestProperty("Accept","application/json");
    try{
      if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
      ByteArrayOutputStream buf=new ByteArrayOutputStream();
      byte[] b=new byte[4096];int n,total=0;
      try(InputStream in=c.getInputStream()){
        while((n=in.read(b))!=-1){total+=n;if(total>300000)throw new IOException("Feed too large");buf.write(b,0,n);}
      }
      return new JSONObject(buf.toString("UTF-8"));
    }finally{c.disconnect();}
  }
  void show(String placement){
    if(dismissed||!enabled||System.currentTimeMillis()-fetchedAt>5*60*1000L)return;
    Offer offer=find(placement.equals("home")?homeId:resultId);
    if(offer==null)return;
    LinearLayout outer=app.col();
    outer.setPadding(app.dp(13),app.dp(14),app.dp(13),app.dp(14));
    outer.setBackground(app.fill(app.panel,app.border,16));
    LinearLayout top=app.row();
    TextView title=app.text("A LITTLE SOMETHING FROM CFG",12,app.accent,true);
    top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    TextView dismiss=app.text("✕",21,app.muted,true);
    dismiss.setGravity(Gravity.CENTER);dismiss.setContentDescription("Hide promotions until app restart");
    dismiss.setOnClickListener(v->dismiss());
    top.addView(dismiss,new LinearLayout.LayoutParams(app.dp(44),app.dp(44)));
    outer.addView(top);
    if(!offer.imageUrl.isEmpty()){
      ImageView image=new ImageView(app);image.setAdjustViewBounds(true);
      image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      LinearLayout.LayoutParams img=new LinearLayout.LayoutParams(-1,app.dp(145));
      img.topMargin=app.dp(4);outer.addView(image,img);loadImage(image,offer.imageUrl);
    }
    TextView headline=app.text(offer.headline,22,app.ink,true);outer.addView(headline);
    TextView sub=app.text(offer.subtitle,14,app.muted,false);
    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=app.dp(7);outer.addView(sub,sp);
    if(!offer.price.isEmpty()){
      TextView value=app.text(offer.price,20,app.accent,true);
      LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.topMargin=app.dp(7);outer.addView(value,vp);
    }
    if(!offer.code.isEmpty()){
      TextView coupon=app.text("CODE  "+offer.code+"    ⧉ COPY",13,app.accent,true);
      coupon.setPadding(0,app.dp(10),0,app.dp(10));
      coupon.setOnClickListener(v->{
        ((android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(
          ClipData.newPlainText("CFG discount",offer.code));
        app.toast("Code copied");
      });outer.addView(coupon);
    }
    Button cta=app.makeButton(offer.cta+" ↗",true,v->{
      // The server must create the cart-preserving link and validate live discounts.
      sendEvent("click",offer,placement);
      Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(offer.clickUrl));
      try{app.startActivity(open);}catch(Exception e){app.toast("Could not open the CFG store");}
    });
    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=app.dp(12);outer.addView(cta,bp);
    app.add(outer,placement.equals("home")?22:18);
    monitorVisibility(outer,offer,placement);
  }
  private void loadImage(ImageView view,String address){
    if(!address.startsWith("https://"))return;
    io.execute(()->{
      Bitmap bmp=null;HttpURLConnection c=null;
      try{
        URL u=new URL(address);String host=u.getHost().toLowerCase(Locale.US);
        if(!(host.equals("cdn.shopify.com")||host.equals(STORE_HOST)||host.equals("www."+STORE_HOST)))return;
        c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(false);
        if(c.getResponseCode()!=200||c.getContentLengthLong()>1200000)return;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];int n,total=0;
        try(InputStream in=c.getInputStream()){
          while((n=in.read(buffer))!=-1){total+=n;if(total>1200000)return;bytes.write(buffer,0,n);}
        }
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        byte[] arr=bytes.toByteArray();BitmapFactory.decodeByteArray(arr,0,arr.length,options);
        if(options.outWidth>0&&options.outHeight>0){
          options.inJustDecodeBounds=false;
          options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/512);
          bmp=BitmapFactory.decodeByteArray(arr,0,arr.length,options);
        }
      }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
      final Bitmap image=bmp;
      if(image!=null)ui.post(()->{if(!closed&&view.getWindowToken()!=null)view.setImageBitmap(image);});
    });
  }
  private void monitorVisibility(View card,Offer offer,String place){
    final android.view.ViewTreeObserver.OnScrollChangedListener[] scrollListener=new android.view.ViewTreeObserver.OnScrollChangedListener[1];
    final Runnable verify=()->{
      if(closed||dismissed||!app.hasWindowFocus())return;
      Rect visible=new Rect();
      if(card.getGlobalVisibleRect(visible)&&card.getHeight()>0&&card.getWidth()>0&&
        visible.height()>=card.getHeight()/2 &&visible.width()>=card.getWidth()/2){
        String key=gameId+":"+place+":"+offer.id;
        if(impressions.add(key))sendEvent("impression",offer,place);
      }
    };
    scrollListener[0]=verify::run;
    card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      public void onViewAttachedToWindow(View v){
        v.getViewTreeObserver().addOnScrollChangedListener(scrollListener[0]);
        v.postDelayed(verify,350);
      }
      public void onViewDetachedFromWindow(View v){
        if(v.getViewTreeObserver().isAlive())v.getViewTreeObserver().removeOnScrollChangedListener(scrollListener[0]);
      }
    });
  }
  void sendEvent(String type,Offer offer,String placement){
    if(metricsUrl.isEmpty()||closed)return;
    final String target=metricsUrl,id=offer.id,region=offer.country;
    io.execute(()->{
      HttpURLConnection c=null;
      try{
        c=(HttpURLConnection)new URL(target).openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(5000);c.setReadTimeout(5000);
        c.setRequestProperty("Content-Type","application/json");
        c.setDoOutput(true);
        JSONObject payload=new JSONObject();payload.put("type",type);payload.put("promotionId",id);
        payload.put("placement",placement);payload.put("country",region);
        try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes("UTF-8"));}
        c.getResponseCode();
      }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
    });
  }
  static final class Offer {
    String id,productId,headline,subtitle,cta,price,code,clickUrl,imageUrl,country;
    int weight;
    static Offer parse(JSONObject data,String requestedCountry){
      if(data==null||!data.optBoolean("approved",false)||!data.optBoolean("verified",false)||
         !data.optBoolean("inStock",false))return null;
      Offer p=new Offer();
      p.id=data.optString("promotionId","");
      p.productId=data.optString("productId","");
      p.headline=data.optString("headline","");
      p.subtitle=data.optString("subtitle","");
      p.cta=data.optString("cta","SHOP THE DEAL");
      p.price=data.optString("formattedPrice","");
      p.code=data.optString("code","");
      p.clickUrl=data.optString("clickUrl","");
      p.imageUrl=data.optString("imageUrl","");
      p.country=data.optString("country","");
      String level=data.optString("priority","Normal");
      p.weight=level.equalsIgnoreCase("High")?5:level.equalsIgnoreCase("Low")?1:3;
      if(p.id.isEmpty()||p.productId.isEmpty()||p.headline.isEmpty()||
         p.price.isEmpty()||!p.country.equals(requestedCountry)||
         !validShopLink(p.clickUrl)||p.id.length()>150)return null;
      long expiry=data.optLong("verifiedUntilMs",0);
      if(expiry<=System.currentTimeMillis()||expiry>System.currentTimeMillis()+15*60*1000L)return null;
      return p;
    }
  }
}
