package com.cablefreeguitar.riffrumble;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  static final String API = "https://cfg-riff-rumble.gonzaley.chatgpt.site";
  static final String QUESTIONS_URL = "https://raw.githubusercontent.com/toty123y/Toty-s/cfg-riff-rumble-android-repair/app/src/main/assets/questions.json";
  static final String QUESTION_CACHE = "questions-remote.json";
  static final long QUESTION_POLL_MS = 30L * 60L * 1000L;
  static final String[] IDS = {"all","rock","gear","legends","theory","studio"};
  static final String[] NAMES = {"The Full Mix","Rock & Bands","Guitar & Gear","Music Legends","Music Theory","Studio & Sound"};
  final Handler ui = new Handler(Looper.getMainLooper());
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  SharedPreferences prefs;
  JSONArray questions = new JSONArray(), soloQuestions;
  JSONObject room;
  LinearLayout content;
  TextView clock, status;
  String screen="home", category="all", code="", playerToken="", hostToken="", playerName="";
  int rounds=10, seconds=20, soloIndex=0, soloPoints=0, soloCorrect=0, soloAnswer=-1, generation=0;
  long deadline=0, clockOffset=0;
  boolean dark=true, solo=false, revealed=false, busy=false, polling=false, active=false, syncingQuestions=false;
  final Runnable questionsTicker=new Runnable(){public void run(){if(active) { syncQuestions(); ui.postDelayed(this,QUESTION_POLL_MS); }};};
  int bg, ink, muted, panel, border, accent, primary, buttonInk;
  final Runnable ticker = new Runnable() { public void run() { if(!active)return; updateClock(); ui.postDelayed(this,300); } };
  final Runnable pollTask = new Runnable() { public void run() { pollRoom(); } };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs=getSharedPreferences("cfg-riff-settings",MODE_PRIVATE);
    dark=prefs.getBoolean("dark",true); playerName=prefs.getString("name","");
    try(InputStream stream=getAssets().open("questions.json")) {
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      byte[] buf=new byte[8192]; int n; while((n=stream.read(buf))!=-1)out.write(buf,0,n);
      questions=new JSONArray(out.toString("UTF-8"));
      if(questions.length()<657 || questions.length()>10000)throw new IOException("Invalid built-in question bank");
    } catch(Exception e) { fail("Unable to load built-in questions",e);return; }
    loadSavedQuestions();
    if(state!=null) {
      screen=state.getString("screen","home"); solo=state.getBoolean("solo",false);
      rounds=state.getInt("rounds",10); seconds=state.getInt("seconds",20); category=state.getString("category","all");
      code=state.getString("code","");playerToken=state.getString("token","");hostToken=state.getString("host","");
      soloIndex=state.getInt("index");soloPoints=state.getInt("points");soloCorrect=state.getInt("correct");soloAnswer=state.getInt("answer",-1);
      deadline=state.getLong("deadline"); revealed=state.getBoolean("revealed",false);
      try { String r=state.getString("room"); if(r!=null)room=new JSONObject(r);
        String q=state.getString("soloQuestions"); if(q!=null)soloQuestions=new JSONArray(q); }
      catch(Exception ignored){screen="home";}
    } else {
      code=prefs.getString("room","");playerToken=prefs.getString("token","");hostToken=prefs.getString("host","");
      if(!code.isEmpty()&&!playerToken.isEmpty())screen="reconnect";
    }
    renderSafe();
    if(state==null && getIntent()!=null)receiveInvite(getIntent());
  }
  @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receiveInvite(i);}
  void receiveInvite(Intent intent) {
    if(intent==null || intent.getData()==null)return;
    Uri link=intent.getData();String roomCode=link.getQueryParameter("room");
    if(roomCode!=null && roomCode.toUpperCase(Locale.US).matches("[A-Z2-9]{6}")) {
      String c=roomCode.toUpperCase(Locale.US);
      if(c.equals(code)&&!playerToken.isEmpty()){screen="reconnect";solo=false;renderSafe();if(active)pollRoom();}
      else {leave();code=c;screen="join";renderSafe();}
    }
  }
  @Override protected void onResume(){super.onResume();active=true;ui.removeCallbacks(ticker);ui.post(ticker);ui.removeCallbacks(questionsTicker);ui.post(questionsTicker);if(!solo&&!code.isEmpty()&&!playerToken.isEmpty())pollRoom();}
  @Override protected void onPause(){active=false;ui.removeCallbacks(ticker);ui.removeCallbacks(pollTask);ui.removeCallbacks(questionsTicker);super.onPause();}
  @Override protected void onDestroy(){generation++;worker.shutdownNow();ui.removeCallbacksAndMessages(null);super.onDestroy();}
  @Override public void onSaveInstanceState(Bundle b){
    super.onSaveInstanceState(b);b.putString("screen",screen);b.putBoolean("solo",solo);b.putInt("rounds",rounds);b.putInt("seconds",seconds);b.putString("category",category);
    b.putString("code",code);b.putString("token",playerToken);b.putString("host",hostToken);
    b.putInt("index",soloIndex);b.putInt("points",soloPoints);b.putInt("correct",soloCorrect);b.putInt("answer",soloAnswer);b.putLong("deadline",deadline);b.putBoolean("revealed",revealed);
    if(room!=null)b.putString("room",room.toString());if(soloQuestions!=null)b.putString("soloQuestions",soloQuestions.toString());
  }

  // The installed APK always has a built-in offline bank.
  // On startup load the last verified downloaded bank, then refresh it in the background.
  // This does not change the hosted multiplayer server's own question database.
  void loadSavedQuestions(){
    File saved=new File(getFilesDir(),QUESTION_CACHE);
    if(!saved.isFile())return;
    try(FileInputStream in=new FileInputStream(saved)){
      JSONArray remote=parseBank(in);
      if(validBank(remote))questions=remote;
    }catch(Exception e){android.util.Log.w("CFG-Riff-Rumble","Ignoring invalid cached questions",e);}
  }
  JSONArray parseBank(InputStream in)throws Exception{
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
    while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>12000000)throw new IOException("Question bank too large");}
    return new JSONArray(bytes.toString("UTF-8"));
  }
  boolean validBank(JSONArray bank){
    if(bank==null||bank.length()<657||bank.length()>10000)return false;
    HashSet<String> prompts=new HashSet<>();
    for(int i=0;i<bank.length();i++){
      JSONObject q=bank.optJSONObject(i);
      if(q==null||q.optString("prompt").trim().length()<8||!prompts.add(q.optString("prompt").trim().toLowerCase(Locale.ROOT)))return false;
      JSONArray options=q.optJSONArray("options");int answer=q.optInt("correct_index",-1);
      if(options==null||options.length()!=4||answer<0||answer>3)return false;
      for(int j=0;j<4;j++)if(options.optString(j).trim().isEmpty())return false;
      if(!Arrays.asList(IDS).contains(q.optString("category")))return false;
    }
    return true;
  }
  void syncQuestions(){
    if(syncingQuestions || System.currentTimeMillis()-prefs.getLong("last_question_sync",0)<15*60*1000L)return;
    syncingQuestions=true;
    worker.execute(()->{
      HttpURLConnection connection=null;JSONArray downloaded=null;String raw=null;
      try{
        connection=(HttpURLConnection)new URL(QUESTIONS_URL).openConnection();
        connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
        connection.setUseCaches(false);connection.setRequestProperty("Cache-Control","no-cache");
        int status=connection.getResponseCode();
        if(status!=200)throw new IOException("Question sync HTTP "+status);
        try(InputStream in=connection.getInputStream()){
          ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
          while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>12000000)throw new IOException("Question bank too large");}
          raw=bytes.toString("UTF-8");downloaded=new JSONArray(raw);
        }
        if(!validBank(downloaded))throw new IOException("Remote questions failed verification");
        File temporary=new File(getFilesDir(),"questions-next.json");
        File stable=new File(getFilesDir(),QUESTION_CACHE);
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(raw.getBytes("UTF-8"));output.getFD().sync();}
        if(!temporary.renameTo(stable)){
          try(FileOutputStream output=new FileOutputStream(stable)){output.write(raw.getBytes("UTF-8"));output.getFD().sync();}
          temporary.delete();
        }
      }catch(Exception e){android.util.Log.w("CFG-Riff-Rumble","Question sync skipped, offline bank remains usable",e);}
      finally{if(connection!=null)connection.disconnect();}
      final JSONArray updated=downloaded;
      ui.post(()->{
        syncingQuestions=false;
        if(updated!=null&&!isFinishing()&&!isDestroyed()&&validBank(updated)){
          questions=updated;
          prefs.edit().putLong("last_question_sync",System.currentTimeMillis()).apply();
          android.util.Log.i("CFG-Riff-Rumble","Questions updated: "+questions.length());
        }
      });
    });
  }
  void fail(String text,Throwable exception) {
    android.util.Log.e("CFG-Riff-Rumble",text,exception);
    try { new AlertDialog.Builder(this).setTitle("CFG Riff Rumble").setMessage(text+"\n"+exception.getClass().getSimpleName()+": "+exception.getMessage()).setCancelable(false).setPositiveButton("Close",(d,w)->finish()).show(); }
    catch(Exception ignored){finish();}
  }
  void renderSafe(){try{render();}catch(Exception e){fail("Failed to show screen",e);}}
  int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  void colors(){
    bg=Color.parseColor(dark?"#090D0A":"#F5F7F1");
    ink=Color.parseColor(dark?"#F6F9F2":"#142112");
    muted=Color.parseColor(dark?"#A4B4A0":"#546352");
    panel=Color.parseColor(dark?"#162016":"#FFFFFF");
    border=Color.parseColor(dark?"#344431":"#CFDECA");
    accent=Color.parseColor(dark?"#B9FB55":"#385D1D");
    primary=Color.parseColor("#B9FB55");buttonInk=Color.parseColor("#152212");
  }
  GradientDrawable fill(int c,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
  LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  TextView text(String value,int sp,int color,boolean bold){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setIncludeFontPadding(true);
    t.setTypeface(Typeface.create(bold?"sans-serif-condensed":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));t.setLineSpacing(dp(2),1f);return t;
  }
  void add(View v,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);content.addView(v,lp);}
  void eyebrow(String s){add(text(s,13,accent,true),22);}
  void headline(String s){add(text(s,39,ink,true),11);}
  void paragraph(String s){add(text(s,16,muted,false),14);}
  Button makeButton(String s,boolean highlight,View.OnClickListener click){
    Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));b.setTextColor(highlight?buttonInk:ink);
    b.setBackground(fill(highlight?primary:panel,highlight?0:border,15));b.setPadding(dp(14),dp(13),dp(14),dp(13));b.setMinimumHeight(dp(56));
    b.setOnClickListener(v->{try{click.onClick(v);}catch(Exception e){fail("Action failed",e);}});return b;
  }
  void action(String s,boolean main,View.OnClickListener click){
    Button b=makeButton(s,main,click);b.setEnabled(!busy);add(b,11);
  }
  EditText input(String hint,String initial,boolean roomCode){
    EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(ink);e.setHintTextColor(muted);e.setTextSize(17);
    e.setPadding(dp(14),dp(14),dp(14),dp(14));e.setBackground(fill(panel,border,12));
    e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(roomCode?android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS:android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS));
    e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(roomCode?6:22)});
    e.setHint(hint);e.setText(initial);add(e,11);return e;
  }
  void nameInput(){
    EditText e=input("Rockstar nickname",playerName,false);
    e.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int st,int c,int a){}
      public void onTextChanged(CharSequence s,int st,int before,int count){playerName=s.toString();prefs.edit().putString("name",playerName).apply();}
      public void afterTextChanged(Editable s){}
    });
  }
  void render(){
    if(isFinishing())return;colors();clock=null;status=null;
    getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
    getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=col();root.setBackgroundColor(bg);
    LinearLayout bar=row();bar.setPadding(dp(20),dp(14),dp(20),dp(12));
    TextView badge=text("CFG",20,buttonInk,true);badge.setGravity(Gravity.CENTER);badge.setBackground(fill(primary,0,10));bar.addView(badge,new LinearLayout.LayoutParams(dp(57),dp(45)));
    TextView brand=text("  RIFF RUMBLE",20,ink,true);bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
    TextView theme=text(dark?"☀ LIGHT":"☾ DARK",13,accent,true);theme.setGravity(Gravity.CENTER);
    theme.setContentDescription("Switch between dark and light themes");
    theme.setOnClickListener(v->{dark=!dark;prefs.edit().putBoolean("dark",dark).apply();renderSafe();});bar.addView(theme,new LinearLayout.LayoutParams(dp(85),dp(48)));
    root.addView(bar);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    content=col();content.setPadding(dp(22),dp(3),dp(22),dp(32));
    scroll.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
    switch(screen){
      case "create":setup();break;
      case "join":join();break;
      case "lobby":lobby();break;
      case "game":game();break;
      case "finished":finishScreen();break;
      case "reconnect":eyebrow("BACKSTAGE PASS");headline("RECONNECTING.");paragraph("Returning to your game room...");action("Retry",true,v->pollRoom());action("Back Home",false,v->home());break;
      default:homeScreen();
    }
    updateClock();
  }
  void back(){TextView b=text("‹  Back",16,muted,true);b.setPadding(0,dp(10),0,dp(10));b.setOnClickListener(v->onBackPressed());add(b,3);}
  void homeScreen(){
    eyebrow("PLAY LOUD. THINK FAST.");headline("TURN UP\nTHE TRIVIA.");
    paragraph("Your riffs. Your friends. Your bragging rights.");
    TextView art=text("⚡\nRIFF\nRUMBLE",43,accent,true);art.setGravity(Gravity.CENTER);art.setPadding(dp(14),dp(21),dp(14),dp(21));art.setBackground(fill(panel,border,20));add(art,24);
    action("Create Game",true,v->{solo=false;screen="create";renderSafe();});
    action("Join Game",false,v->{solo=false;screen="join";renderSafe();});
    action("Play Solo · Offline",false,v->{solo=true;screen="create";renderSafe();});
    eyebrow("ROCK · GEAR · LEGENDS · THEORY · STUDIO");paragraph("Built for music people. Fresh questions, online rooms, light and dark mode.");
  }
  void setup(){
    back();eyebrow(solo?"OFFLINE SOLO SESSION":"CREATE ONLINE GAME");headline("BUILD YOUR\nSETLIST.");nameInput();
    eyebrow("HOW MANY ROUNDS?");choices(new int[]{5,10,15,20},false);
    eyebrow("SECONDS PER QUESTION");choices(new int[]{10,15,20,30,45},true);
    eyebrow("PICK A CATEGORY");
    for(int i=0;i<IDS.length;i++){String id=IDS[i];action((category.equals(id)?"✓  ":"")+NAMES[i],category.equals(id),v->{category=id;renderSafe();});}
    action(busy?"Please wait...":solo?"LET'S ROCK":"CREATE ROOM",true,v->{if(solo)startSolo();else createRoom();});
  }
  void choices(int[] values,boolean time){
    LinearLayout choices=row();
    for(int value:values){boolean chosen=(time?seconds:rounds)==value;
      Button b=makeButton(value+(time?"s":""),chosen,v->{if(time)seconds=value;else rounds=value;renderSafe();});
      b.setTextSize(12);b.setPadding(dp(3),dp(8),dp(3),dp(8));
      LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.rightMargin=dp(4);choices.addView(b,params);}
    add(choices,11);
  }
  void join(){
    back();eyebrow("GOT AN INVITE?");headline("JOIN THE JAM.");paragraph("Enter your six-character room code.");
    EditText roomInput=input("ROOM CODE",code,true);nameInput();
    action(busy?"Joining...":"JOIN GAME",true,v->{
      String c=roomInput.getText().toString().trim().toUpperCase(Locale.US);
      if(!c.matches("[A-Z2-9]{6}")){toast("Please enter a valid room code.");return;}
      code=c;joinRoom();
    });
  }
  void lobby(){
    if(room==null){screen="reconnect";renderSafe();return;}
    back();eyebrow("YOUR ROOM IS LIVE");headline("THE STAGE\nIS SET.");
    TextView codeLabel=text(code,43,accent,true);codeLabel.setGravity(Gravity.CENTER);
    codeLabel.setBackground(fill(panel,border,15));codeLabel.setPadding(dp(10),dp(22),dp(10),dp(22));add(codeLabel,18);
    action("Share Invite",true,v->share());
    action("Copy Room Code",false,v->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CFG room",code));toast("Code copied");});
    paragraph(room.optInt("rounds")+" rounds · "+room.optInt("seconds")+" seconds per question");
    eyebrow("PLAYERS READY · "+room.optInt("playerCount"));players(room.optJSONArray("players"),false);
    if(room.optBoolean("isHost"))action(busy?"Starting...":"LET'S ROCK",true,v->startOnline());
    else paragraph("Waiting for the host to start...");
    status=text("Connected",13,muted,false);add(status,16);
  }
  void game(){
    JSONObject q=solo?(soloQuestions==null?null:soloQuestions.optJSONObject(soloIndex)):(room==null?null:room.optJSONObject("question"));
    if(q==null){paragraph("Preparing the next round...");return;}
    int questionIndex=solo?soloIndex:room.optInt("questionIndex");
    boolean show=solo?revealed:"reveal".equals(room.optString("phase"));
    JSONObject mine=solo?null:room.optJSONObject("myAnswer");
    int selected=solo?soloAnswer:(mine==null?-1:mine.optInt("choiceIndex",-1));
    int answer=solo?q.optInt("correct_index",-1):(show?q.optInt("correctIndex",-1):-1);
    eyebrow((solo?"SOLO":"LIVE "+code)+" · "+q.optString("category").toUpperCase(Locale.US));
    headline("ROUND "+(questionIndex+1)+" / "+(solo?rounds:room.optInt("rounds")));
    clock=text("",31,accent,true);add(clock,15);
    TextView question=text(q.optString("prompt"),25,ink,true);add(question,25);
    JSONArray options=q.optJSONArray("options");if(options==null){paragraph("Unable to load the choices.");return;}
    for(int i=0;i<options.length();i++){final int chosen=i;
      String prefix=show&&i==answer?"✓  ":selected==i?(show?"✕  ":"●  "):((char)('A'+i))+". ";
      Button b=makeButton(prefix+options.optString(i),show&&i==answer||!show&&selected==i,v->choose(chosen,questionIndex));
      b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setMinHeight(dp(65));b.setEnabled(!busy&&!show&&selected<0);
      if(show&&selected==i&&i!=answer){b.setBackground(fill(Color.rgb(115,42,42),0,12));b.setTextColor(Color.WHITE);}
      add(b,10);
    }
    if(show){
      eyebrow(selected==answer?"NAILED IT!":selected<0?"TIME'S UP":"NOT THIS TIME");
      paragraph(q.optString("explanation"));
      if(solo)action(soloIndex+1>=rounds?"FINAL SCORE":"NEXT QUESTION",true,v->nextSolo());
    }else paragraph(selected<0?"Correct answers score more the faster you respond.":"Answer locked. Waiting for the reveal...");
    if(solo)eyebrow("YOUR SCORE · "+soloPoints);else {eyebrow("LIVE LEADERBOARD");players(room.optJSONArray("players"),true);}
  }
  void players(JSONArray list,boolean showScore){
    if(list==null)return;
    for(int i=0;i<list.length();i++){
      JSONObject p=list.optJSONObject(i);if(p==null)continue;
      TextView v=text((i+1)+".  "+p.optString("name")+(showScore?"     "+p.optInt("score"):""),17,ink,true);
      v.setPadding(dp(15),dp(13),dp(15),dp(13));v.setBackground(fill(panel,border,11));add(v,8);
    }
  }
  void finishScreen(){
    eyebrow("AMPS OFF. SCORES IN.");headline(solo?"YOU ROCKED\nTHE SET.":"FINAL ENCORE.");
    if(solo){headline(soloPoints+" POINTS");paragraph(soloCorrect+" / "+rounds+" correct");}
    else if(room!=null){JSONArray p=room.optJSONArray("players");if(p!=null&&p.length()>0)paragraph("CHAMPION: "+p.optJSONObject(0).optString("name"));players(p,true);}
    action("PLAY AGAIN",true,v->{boolean oldSolo=solo;leave();solo=oldSolo;screen="create";renderSafe();});
    action("BACK HOME",false,v->home());
  }
  void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  void share(){
    Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
    i.putExtra(Intent.EXTRA_TEXT,"Join CFG Riff Rumble! Room code: "+code+"\n"+API+"/?room="+code);
    startActivity(Intent.createChooser(i,"Invite friends"));
  }
  void leave(){
    generation++;busy=false;polling=false;ui.removeCallbacks(pollTask);
    code="";playerToken="";hostToken="";room=null;soloQuestions=null;solo=false;
    prefs.edit().remove("room").remove("token").remove("host").apply();
  }
  void home(){leave();screen="home";renderSafe();}
  @Override public void onBackPressed(){
    if(screen.equals("home")){super.onBackPressed();return;}
    if(screen.equals("game")||screen.equals("lobby")||screen.equals("reconnect")){
      new AlertDialog.Builder(this).setTitle("Leave this game?")
      .setMessage("Your friends can keep playing.")
      .setPositiveButton("Leave",(d,w)->home()).setNegativeButton("Stay",null).show();
    }else home();
  }
  void startSolo(){
    ArrayList<JSONObject> available=new ArrayList<>();
    for(int i=0;i<questions.length();i++){
      JSONObject q=questions.optJSONObject(i);
      if(q!=null&&(category.equals("all")||category.equals(q.optString("category"))))available.add(q);
    }
    Collections.shuffle(available);soloQuestions=new JSONArray();
    for(int i=0;i<Math.min(rounds,available.size());i++)soloQuestions.put(available.get(i));
    rounds=soloQuestions.length();if(rounds==0){toast("No questions available");return;}
    soloIndex=0;soloPoints=0;soloCorrect=0;soloAnswer=-1;revealed=false;deadline=System.currentTimeMillis()+seconds*1000L;screen="game";renderSafe();
  }
  void nextSolo(){
    if(soloIndex+1>=rounds){screen="finished";renderSafe();return;}
    soloIndex++;soloAnswer=-1;revealed=false;deadline=System.currentTimeMillis()+seconds*1000L;renderSafe();
  }
  void choose(int selected,int questionIndex){
    if(solo){
      if(revealed||soloAnswer>=0||System.currentTimeMillis()>deadline)return;
      soloAnswer=selected;JSONObject q=soloQuestions.optJSONObject(soloIndex);
      if(q!=null&&selected==q.optInt("correct_index")){soloCorrect++;soloPoints+=1000+(int)(500.0*Math.max(0,deadline-System.currentTimeMillis())/(seconds*1000L));}
      revealed=true;renderSafe();return;
    }
    if(busy||room==null||room.optJSONObject("myAnswer")!=null)return;
    busy=true;renderSafe();
    api("/api/rooms/"+code+"/answer","POST",object("choiceIndex",selected,"questionIndex",questionIndex),(r,e)->{
      busy=false;if(e!=null)toast(e);pollRoom();renderSafe();
    });
  }
  void updateClock(){
    if(!screen.equals("game")||clock==null)return;
    boolean show=solo?revealed:room!=null&&"reveal".equals(room.optString("phase"));
    long end=solo?deadline:(room==null?0:room.optLong("deadlineMs"));
    long now=System.currentTimeMillis()+(solo?0:clockOffset);
    long secs=Math.max(0,(end-now+999)/1000);
    clock.setText(show?"REVEAL":secs+"s");
    if(solo&&!revealed&&secs==0){revealed=true;renderSafe();}
  }
  interface Reply { void done(JSONObject result,String problem); }
  JSONObject object(Object... pairs){
    JSONObject j=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)j.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}
    return j;
  }
  void api(String path,String method,JSONObject data,Reply callback){
    final int revision=generation;final String pToken=playerToken,hToken=hostToken;
    worker.execute(()->{
      HttpURLConnection c=null;JSONObject output=null;String problem=null;
      try{
        c=(HttpURLConnection)new URL(API+path).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","CFG-Riff-Rumble-Android/2.2");
        if(!pToken.isEmpty())c.setRequestProperty("X-Player-Token",pToken);
        if(!hToken.isEmpty())c.setRequestProperty("X-Host-Token",hToken);
        if(data!=null){
          c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
          try(OutputStream out=c.getOutputStream()){out.write(data.toString().getBytes("UTF-8"));}
        }
        int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
        if(stream==null)throw new IOException("Empty response");
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        try(InputStream in=stream){while((n=in.read(buffer))!=-1)b.write(buffer,0,n);}
        output=new JSONObject(b.toString("UTF-8"));
        if(status>=400)problem=output.optString("error","Server error: "+status);
      }catch(Exception e){problem="Connection unavailable. Check Wi-Fi or mobile data.";}
      finally{if(c!=null)c.disconnect();}
      final JSONObject result=output;final String error=problem;
      ui.post(()->{if(revision==generation&&!isFinishing())callback.done(result,error);});
    });
  }
  void persist(JSONObject payload){
    if(payload==null){toast("Empty server response");return;}
    code=payload.optString("code",code);
    playerToken=payload.optString("playerToken",playerToken);
    hostToken=payload.optString("hostToken",hostToken);
    prefs.edit().putString("room",code).putString("token",playerToken).putString("host",hostToken).apply();
    accept(payload.optJSONObject("state"));
  }
  void createRoom(){
    if(busy)return;busy=true;renderSafe();
    api("/api/rooms","POST",object("name",playerName.trim().isEmpty()?"Rockstar":playerName.trim(),
      "rounds",rounds,"seconds",seconds,"category",category),(r,e)->{
      busy=false;if(e!=null){toast(e);renderSafe();}else{persist(r);queuePoll();}
    });
  }
  void joinRoom(){
    if(busy)return;busy=true;renderSafe();
    api("/api/rooms/"+code+"/join","POST",object("name",playerName.trim().isEmpty()?"Rockstar":playerName.trim()),(r,e)->{
      busy=false;if(e!=null){toast(e);renderSafe();}else{persist(r);queuePoll();}
    });
  }
  void startOnline(){
    if(busy)return;busy=true;renderSafe();
    api("/api/rooms/"+code+"/start","POST",object(),(r,e)->{
      busy=false;if(e!=null){toast(e);renderSafe();}
      else if(r!=null)accept(r.optJSONObject("state"));queuePoll();
    });
  }
  void pollRoom(){
    if(!active||solo||code.isEmpty()||playerToken.isEmpty()||polling)return;
    polling=true;api("/api/rooms/"+code,"GET",null,(r,e)->{
      polling=false;if(e==null)accept(r);else if(status!=null)status.setText(e);
      queuePoll();
    });
  }
  void queuePoll(){
    ui.removeCallbacks(pollTask);
    if(active&&!solo&&!playerToken.isEmpty()&&!screen.equals("finished"))ui.postDelayed(pollTask,1550);
  }
  void accept(JSONObject response){
    if(response==null)return;
    String before=room==null?"":signature(room);
    room=response;clockOffset=response.optLong("serverNowMs",System.currentTimeMillis())-System.currentTimeMillis();
    String stage=response.optString("status");
    String next=stage.equals("lobby")?"lobby":stage.equals("finished")?"finished":"game";
    if(!next.equals(screen)||!before.equals(signature(response))){screen=next;renderSafe();}
    else if(status!=null)status.setText("Connected");
  }
  String signature(JSONObject v){
    return v.optString("status")+v.optString("phase")+v.optInt("questionIndex")
      +String.valueOf(v.optJSONArray("players"))+String.valueOf(v.optJSONObject("myAnswer"))+v.optBoolean("isHost");
  }
}