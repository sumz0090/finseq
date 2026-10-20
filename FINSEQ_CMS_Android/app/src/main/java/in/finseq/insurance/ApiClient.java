package in.finseq.insurance;
import android.content.*;import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;
public final class ApiClient {
 public static String BASE="https://cms.finseq.in";
 static JSONObject request(Context c,String method,String path,JSONObject body)throws Exception{
  URL url=new URL(BASE+path);HttpURLConnection h=(HttpURLConnection)url.openConnection();h.setRequestMethod(method);h.setConnectTimeout(15000);h.setReadTimeout(path.endsWith("/activity/sync")||path.endsWith("/mandates/sync")||path.endsWith("/sip/sync")?600000:45000);h.setRequestProperty("Accept","application/json");String t=Session.token(c);if(!t.isEmpty())h.setRequestProperty("Authorization","Bearer "+t);
  if(body!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream os=h.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
  int code=h.getResponseCode();InputStream is=(code>=200&&code<400)?h.getInputStream():h.getErrorStream();StringBuilder sb=new StringBuilder();if(is!=null)try(BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}JSONObject j=sb.length()==0?new JSONObject():new JSONObject(sb.toString());if(code<200||code>=300){if(code==401)Session.clear(c);throw new ApiException(code,j.optString("error","Server error"));}return j;
 }
 static JSONObject get(Context c,String p)throws Exception{return request(c,"GET",p,null);} static JSONObject post(Context c,String p,JSONObject b)throws Exception{return request(c,"POST",p,b);}
 static JSONObject uploadPdf(Context c,File file)throws Exception{
  HttpURLConnection h=(HttpURLConnection)new URL(BASE+"/api/v1/policies/upload-temp").openConnection();
  h.setRequestMethod("POST");h.setConnectTimeout(20000);h.setReadTimeout(600000);h.setDoOutput(true);
  h.setRequestProperty("Accept","application/json");h.setRequestProperty("Content-Type","application/pdf");h.setRequestProperty("Authorization","Bearer "+Session.token(c));
  long length=file.length();if(length<=0)throw new IOException("Selected PDF is empty");h.setFixedLengthStreamingMode(length);
  try(InputStream in=new FileInputStream(file);OutputStream out=h.getOutputStream()){byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
  int code=h.getResponseCode();InputStream is=code<400?h.getInputStream():h.getErrorStream();StringBuilder sb=new StringBuilder();if(is!=null)try(BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}
  JSONObject j=sb.length()==0?new JSONObject():new JSONObject(sb.toString());h.disconnect();if(code<200||code>=300){if(code==401)Session.clear(c);throw new ApiException(code,j.optString("error","PDF upload failed"));}return j;
 }

 static String attachmentType(File file)throws IOException{try(InputStream in=new FileInputStream(file)){byte[] head=new byte[1024];int n=in.read(head);if(n>=3&&(head[0]&255)==255&&(head[1]&255)==216&&(head[2]&255)==255)return "jpg";if(n>=8&&(head[0]&255)==137&&head[1]=='P'&&head[2]=='N'&&head[3]=='G')return "png";for(int i=0;i<=n-5;i++)if(head[i]=='%'&&head[i+1]=='P'&&head[i+2]=='D'&&head[i+3]=='F'&&head[i+4]=='-')return "pdf";throw new IOException("Document header is not PDF/JPG/PNG");}}
 static File pdf(Context c,int policyId)throws Exception{
  Exception first;
  try{return fetchAttachment(c,policyId,"/api/v1/policies/"+policyId+"/pdf");}
  catch(ApiException error){if(error.code==401||error.code==403)throw error;first=error;}
  catch(IOException error){first=error;}
  try{return fetchAttachment(c,policyId,"/policies/"+policyId+"/pdf");}
  catch(Exception second){throw new IOException("Policy #"+policyId+" attachment could not be opened. App API: "+first.getMessage()+"; web PDF: "+second.getMessage(),second);}
 }
 static File fetchAttachment(Context c,int policyId,String route)throws Exception{
  HttpURLConnection h=(HttpURLConnection)new URL(BASE+route).openConnection();
  h.setInstanceFollowRedirects(false);h.setConnectTimeout(15000);h.setReadTimeout(90000);h.setRequestProperty("Accept","application/pdf, image/jpeg, image/png");h.setRequestProperty("Authorization","Bearer "+Session.token(c));if(route.startsWith("/policies/"))h.setRequestProperty("Cookie","finseq_session="+Session.token(c));
  int status=h.getResponseCode();if(status!=200){String message="Attachment unavailable";try(InputStream err=h.getErrorStream()){if(err!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=err.read(chunk))>0&&out.size()<16384)out.write(chunk,0,n);message=new JSONObject(out.toString("UTF-8")).optString("error",message);}}catch(Exception ignored){}h.disconnect();if(status>=300&&status<400)message="Redirected to login or another page";throw new ApiException(status,route+" returned HTTP "+status+": "+message);}
  String content=h.getContentType();if(content!=null&&(content.toLowerCase().contains("text/html")||content.toLowerCase().contains("application/json"))){h.disconnect();throw new IOException(route+" returned "+content+" instead of a document");}
  File file=new File(c.getCacheDir(),"preview-policy-"+policyId+".bin");
  try(InputStream in=h.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}finally{h.disconnect();}
  try{attachmentType(file);}catch(IOException error){file.delete();throw new IOException(route+" returned "+file.length()+" bytes, type "+String.valueOf(content)+", but no PDF/JPG/PNG header was found",error);}
  return file;
 }
 static class ApiException extends Exception{final int code;ApiException(int c,String m){super(m);code=c;}}
}
