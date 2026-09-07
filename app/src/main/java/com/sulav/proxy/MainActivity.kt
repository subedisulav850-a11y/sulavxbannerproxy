package com.sulav.proxy

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val BG=0xFF080B12.toInt(); private const val PANEL=0xFF111827.toInt(); private const val PANEL2=0xFF172235.toInt()
private const val PURPLE=0xFF8B55F5.toInt(); private const val PURPLE2=0xFFA78BFA.toInt(); private const val CYAN=0xFF67D9E8.toInt()
private const val TEXT=0xFFF2F4F8.toInt(); private const val MUTED=0xFF8D96A8.toInt(); private const val GREEN=0xFF52D273.toInt(); private const val RED=0xFFE45D6A.toInt()
private const val DEFAULT_TARGET="https://example.com"

data class Capture(val method:String,val url:String,val status:Int,val requestHex:String,val responseHex:String,val headers:String)

class MainActivity:Activity(){
 private val executor=Executors.newCachedThreadPool(); private val captures=mutableListOf<Capture>(); private lateinit var content:LinearLayout
 private lateinit var packetCountView:TextView
 private var proxy:ProxyServer?=null
 private var proxyRunning=false
 private var target=DEFAULT_TARGET
 override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=BG;window.navigationBarColor=BG;target=getPreferences(0).getString("target",target)?:target;buildShell();showCapture()}
 override fun onDestroy(){proxy?.stop();executor.shutdownNow();super.onDestroy()}
 private fun buildShell(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8));setBackgroundColor(PANEL)}
  top.addView(TextView(this).apply{text="◆";textSize=24f;setTextColor(PURPLE2)},LinearLayout.LayoutParams(dp(42),dp(54)))
  top.addView(TextView(this).apply{text="Sulav Proxy";textSize=20f;typeface=Typeface.DEFAULT_BOLD;setTextColor(TEXT)},LinearLayout.LayoutParams(0,dp(54),1f))
  packetCountView=TextView(this).apply{text="0 packets";textSize=11f;setTextColor(MUTED)}
  top.addView(packetCountView)
  content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG)}
  root.addView(top);root.addView(ScrollView(this).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
  val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(PANEL);setPadding(dp(6),dp(6),dp(6),dp(6))}
  listOf("Capture","Request","Decoder","Updates","Account").forEach{n->val v=TextView(this).apply{text=n.uppercase(Locale.US);textSize=9f;gravity=Gravity.CENTER;setTextColor(TEXT)};v.setOnClickListener{when(n){"Capture"->showCapture();"Request"->showRequest();"Decoder"->showDecoder();"Updates"->showUpdates();"Account"->showAccount()}};nav.addView(v,LinearLayout.LayoutParams(0,dp(58),1f))}
  root.addView(nav);setContentView(root)
 }
 private fun reset(t:String,s:String?=null){content.removeAllViews();addText(t,25f,TEXT,true,18,14);if(s!=null)addText(s,13f,MUTED,false,18,2)}
 private fun addText(s:String,size:Float,color:Int,bold:Boolean=false,left:Int=18,top:Int=8){content.addView(TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD;setPadding(dp(left),dp(top),dp(18),dp(5))})}
 private fun card():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(16));background=rounded(PANEL,0xFF27334A.toInt(),1,20);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(18),dp(10),dp(18),dp(10))}}
 private fun rounded(fill:Int,stroke:Int,width:Int,radius:Int)=GradientDrawable().apply{setColor(fill);setStroke(dp(width),stroke);cornerRadius=dp(radius).toFloat()}
 private fun label(s:String){addText(s,12f,CYAN,true,36,2)}
 private fun edit(h:String,v:String="",multi:Boolean=false)=EditText(this).apply{hint=h;setText(v);textSize=14f;setTextColor(TEXT);setHintTextColor(0xFF657084.toInt());setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(PANEL2);if(multi){minLines=6;gravity=Gravity.TOP;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}else inputType=InputType.TYPE_CLASS_TEXT}
 private fun button(t:String,action:()->Unit)=Button(this).apply{text=t;textSize=12f;setTextColor(Color.WHITE);background=rounded(PURPLE,PURPLE,1,16);isAllCaps=false;setOnClickListener{action()};stateListAnimator=null;layoutParams=LinearLayout.LayoutParams(-1,dp(54)).apply{setMargins(0,dp(8),0,0)}}
 private fun addTextTo(p:LinearLayout,s:String,size:Float,color:Int,bold:Boolean=false){p.addView(TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(5),0,dp(5))})}
 private fun showCapture(){reset("CAPTURE CONSOLE",if(proxyRunning) "RUNNING • 0.0.0.0:$proxyPort" else "IDLE • local HTTP forwarder");val c=card();label("FORWARD TARGET");val e=edit("https://example.com",target);c.addView(e);c.addView(button("SAVE TARGET"){target=e.text.toString().trim();getPreferences(0).edit().putString("target",target).apply();toast("Target saved")});label("LOCAL CAPTURE");val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val start=button(if(proxyRunning)"STOP PROXY" else "START PROXY"){if(proxyRunning)stopProxy() else startProxy()};row.addView(start,LinearLayout.LayoutParams(0,dp(54),1f));val clear=button("CLEAR LOG"){captures.clear();updatePacketCount();showCapture()};row.addView(clear,LinearLayout.LayoutParams(0,dp(54),1f));c.addView(row);c.addView(button("VIEW CAPTURED REQUESTS"){showCaptureLog()});content.addView(c);addText(if(captures.isEmpty())"NO PACKETS YET\n\nStart the local proxy or use Request to test an endpoint you control." else "${captures.size} captured request(s)",14f,MUTED,false,20,22)}
 private val proxyPort:Int get()=getPreferences(0).getString("proxy_port","8080")?.toIntOrNull()?:8080
 private fun updatePacketCount(){if(::packetCountView.isInitialized)packetCountView.text="${captures.size} packets"}
 private fun startProxy(){val port=proxyPort;proxy=ProxyServer("0.0.0.0",port,target,{cap->synchronized(captures){captures.add(cap)};runOnUiThread{updatePacketCount()}},{msg->runOnUiThread{toast(msg)}});proxy!!.start();proxyRunning=true;updatePacketCount();showCapture()}
 private fun stopProxy(){proxy?.stop();proxy=null;proxyRunning=false;showCapture()}
 private fun showCaptureLog(){reset("CAPTURED PACKETS","${captures.size} request(s)");captures.asReversed().forEach{cap->val c=card();addTextTo(c,"${cap.method}  ${cap.status}",14f,if(cap.status in 200..399)GREEN else RED,true);addTextTo(c,cap.url,12f,TEXT);c.addView(button("VIEW DETAIL"){showDetail(cap)});content.addView(c)}}
 private fun showDetail(cap:Capture){reset("REQUEST DETAIL","${cap.method} ${cap.url}");val c=card();addTextTo(c,"STATUS ${cap.status}",14f,if(cap.status in 200..399)GREEN else RED,true);addTextTo(c,"HEADERS",12f,CYAN,true);addTextTo(c,cap.headers.ifBlank{"—"},11f,TEXT);addTextTo(c,"REQUEST HEX",12f,CYAN,true);addTextTo(c,cap.requestHex.ifBlank{"—"},10f,TEXT);addTextTo(c,"RESPONSE HEX",12f,CYAN,true);addTextTo(c,cap.responseHex.ifBlank{"—"},10f,TEXT);content.addView(c)}
 private fun showRequest(){reset("REQUEST SEND","Send a request to an endpoint you control.");val c=card();label("REQUEST BLOCK");val m=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("GET","POST","PUT","DELETE"))};c.addView(m);val u=edit("URL",target);c.addView(u);val h=edit("Headers (Name: Value per line)","",true);c.addView(h);val body=edit("Payload / body","",true);c.addView(body);c.addView(button("SEND REQUEST"){sendRequest(m.selectedItem.toString(),u.text.toString().trim(),h.text.toString(),body.text.toString())});content.addView(c);addText("For safety, this sender does not extract authentication tokens or modify third-party game traffic.",12f,MUTED,false,20,12)}
 private fun sendRequest(method:String,urlText:String,headerText:String,bodyText:String){executor.execute{var conn:HttpURLConnection?=null;try{conn=URL(urlText).openConnection() as HttpURLConnection;conn!!.requestMethod=method;conn!!.connectTimeout=12000;conn!!.readTimeout=12000;headerText.lines().forEach{p->val i=p.indexOf(':');if(i>0)conn!!.setRequestProperty(p.substring(0,i).trim(),p.substring(i+1).trim())};if(method!="GET"&&method!="DELETE"){conn!!.doOutput=true;conn!!.outputStream.use{it.write(bodyText.toByteArray())}};val status=conn!!.responseCode;val stream=if(status>=400)conn!!.errorStream else conn!!.inputStream;val bytes=stream?.let{BufferedInputStream(it).use{inp->readAll(inp)}}?:ByteArray(0);val safe=headerText.lines().joinToString("\n"){if(it.lowercase(Locale.US).startsWith("authorization:"))"Authorization: [REDACTED]" else it};val cap=Capture(method,urlText,status,hex(bodyText.toByteArray(),512),hex(bytes,512),safe);synchronized(captures){captures.add(cap)};runOnUiThread{updatePacketCount();toast("Response $status");showDetail(cap)}}catch(e:Exception){runOnUiThread{toast("Request failed: ${e.message?:"error"}")}}finally{conn?.disconnect()}}}
 private fun showDecoder(){reset("PROTOBUF DECODER","Decode generic response bytes supplied by you.");val c=card();label("RESPONSE HEX");val i=edit("08 03 12 …","",true);c.addView(i);c.addView(button("DECODE"){showDecoded(decodeHex(i.text.toString()))});c.addView(button("CLEAR"){i.setText("")});content.addView(c);addText("DECODED JSON / FALLBACK",12f,CYAN,true,20,16);addText("—",12f,TEXT,false,20,6)}
 private fun showDecoded(b:ByteArray){reset("PROTOBUF DECODER","Decoded byte content");val c=card();addTextTo(c,"DECODED / FALLBACK",12f,CYAN,true);addTextTo(c,b.toString(Charsets.UTF_8).ifBlank{"—"},12f,TEXT);addTextTo(c,"HEX",12f,CYAN,true);addTextTo(c,hex(b,4096),10f,TEXT);content.addView(c);c.addView(button("COPY HEX"){val cm=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager;cm.setPrimaryClip(android.content.ClipData.newPlainText("hex",hex(b,4096)));toast("Copied")})}
 private fun showUpdates(){reset("UPDATES","App update information");val c=card();addTextTo(c,"You are up to date",22f,PURPLE2,true);addTextTo(c,"Version 1.0 is the current build.",15f,TEXT);c.addView(button("CHECK AGAIN"){toast("No update endpoint configured")});content.addView(c)}
 private fun showAccount(){reset("ACCOUNT","Local app profile");val c=card();addTextTo(c,"PROFILE",12f,CYAN,true);addTextTo(c,"Sulav Proxy Owner",21f,TEXT,true);addTextTo(c,"Local profile • Device bound",13f,MUTED);c.addView(button("CHANGE PHOTO"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},1001)});c.addView(button("SAVE PROFILE"){toast("Profile saved locally")});content.addView(c);val s=card();addTextTo(s,"ACCESS",12f,CYAN,true);addTextTo(s,"Device                         Bound",16f,TEXT);addTextTo(s,"SUBSCRIPTION",12f,CYAN,true);addTextTo(s,"Local mode • No subscription server configured",13f,MUTED);content.addView(s)}
 private fun decodeHex(s:String):ByteArray{val c=s.replace("0x","",true).replace(Regex("[^0-9A-Fa-f]"),"");if(c.length%2!=0)return ByteArray(0);return ByteArray(c.length/2){i->c.substring(i*2,i*2+2).toInt(16).toByte()}}
 private fun hex(b:ByteArray,n:Int)=b.copyOfRange(0,minOf(b.size,n)).joinToString(" "){String.format("%02X",it)}
 private fun readAll(i:BufferedInputStream):ByteArray{val o=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=i.read(b);if(n<=0)break;o.write(b,0,n)};return o.toByteArray()}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
