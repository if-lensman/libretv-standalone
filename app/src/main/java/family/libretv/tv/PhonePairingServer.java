package family.libretv.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use LAN form, active only while the TV pairing page is visible. */
final class PhonePairingServer implements AutoCloseable {
    static final long LIFETIME_MS = 5 * 60 * 1000;
    private final ServerSocket listener;
    private final Set<Socket> connections = Collections.synchronizedSet(new HashSet<>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final AtomicBoolean used = new AtomicBoolean();
    private final long expires;
    private final String host, token, defaultNas;
    private final Submission submission;
    private volatile SourceSubmission sourceSubmission;
    interface SourceSubmission { void accept(String config); }
    PhonePairingServer(String address, SourceSubmission submission) throws IOException {
        this(address,"",(origin,password)->{}); sourceSubmission=submission;
    }
    private volatile boolean closed;
    interface Submission { void accept(URI origin, String password); }
    PhonePairingServer(String address, String defaultNas, Submission submission) throws IOException {
        this(address,defaultNas,submission,LIFETIME_MS);
    }
    PhonePairingServer(String address, String defaultNas, Submission submission, long lifetimeMs) throws IOException {
        this.defaultNas = defaultNas; this.submission = submission;
        expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMs);
        listener = new ServerSocket(); listener.bind(new InetSocketAddress(0), 4);
        host = address + ":" + listener.getLocalPort();
        byte[] nonce = new byte[24]; new SecureRandom().nextBytes(nonce); token = AppUpdater.hex(nonce);
        Thread acceptor = new Thread(this::accept, "libretv-phone-config"); acceptor.setDaemon(true); acceptor.start();
    }
    String url() { return "http://"+host+"/"+token; }
    int port() { return listener.getLocalPort(); }
    static List<String> localAddresses() throws SocketException {
        ArrayList<String> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            if (!nic.isUp() || nic.isLoopback()) continue;
            Enumeration<InetAddress> values = nic.getInetAddresses();
            while (values.hasMoreElements()) {
                InetAddress value = values.nextElement();
                if (value instanceof Inet4Address && value.isSiteLocalAddress() && !value.isLoopbackAddress()) {
                    String address = value.getHostAddress();
                    if (nic.getName().startsWith("wlan") || nic.getName().startsWith("eth")) addresses.add(0,address);
                    else addresses.add(address);
                }
            }
        }
        return addresses;
    }
    private void accept() {
        while (!closed) {
            Socket socket = null;
            try {
                socket = listener.accept(); socket.setSoTimeout(4000); connections.add(socket);
                Socket current = socket; workers.execute(() -> handle(current));
            } catch (IOException | RejectedExecutionException ex) {
                if (socket != null) { connections.remove(socket); try { socket.close(); } catch (IOException ignored) { } }
            }
        }
    }
    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
        while ((value=in.read()) != -1) {
            if (value=='\n') return new String(bytes.toByteArray(),StandardCharsets.US_ASCII).replace("\r", "");
            if (bytes.size() >= max) throw new IOException("Header too large");
            bytes.write(value);
        }
        throw new EOFException();
    }
    private void handle(Socket socket) {
        try (Socket current = socket) {
            InputStream in = current.getInputStream(); OutputStream out = current.getOutputStream();
            String[] request = line(in,2048).split(" ");
            if (request.length != 3) { reply(out,400,"请求无效"); return; }
            HashMap<String,String> headers = new HashMap<>(); int total=0;
            while (true) {
                String header = line(in,2048); if (header.isEmpty()) break;
                total += header.length(); if (total>8192) { reply(out,413,"请求过大"); return; }
                int colon=header.indexOf(':'); if (colon<=0) { reply(out,400,"请求无效"); return; }
                String name=header.substring(0,colon).toLowerCase(Locale.ROOT);
                if (headers.put(name,header.substring(colon+1).trim())!=null) { reply(out,400,"重复请求头"); return; }
            }
            String requestedHost=headers.get("host");
            boolean local = socket.getInetAddress().isLoopbackAddress() && ("127.0.0.1:"+port()).equals(requestedHost);
            if (!host.equals(requestedHost) && !local) { reply(out,403,"地址不匹配，请重新扫码"); return; }
            if (!request[1].equals("/"+token) && !request[1].equals("/"+token+"/config")) { reply(out,404,"链接无效，请重新扫码"); return; }
            if (closed || System.nanoTime()>=expires || used.get()) { reply(out,410,"本次配置已结束，请在电视上重新生成二维码"); return; }
            if ("GET".equals(request[0]) && request[1].equals("/"+token)) { reply(out,200,form()); return; }
            if (!"POST".equals(request[0]) || !request[1].endsWith("/config")) { reply(out,405,"请求方式无效"); return; }
            if (headers.containsKey("transfer-encoding") || !value(headers,"content-type", "").toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
                reply(out,415,"请使用扫码后的配置表单"); return;
            }
            String origin=headers.get("origin");
            // WebKit/privacy modes may serialize an opaque Origin as "null". The 192-bit
            // single-use URL, strict Host and explicit TV confirmation are still required.
            if (origin!=null && !origin.equals("null") && !origin.equals("http://"+requestedHost)) { reply(out,403,"不允许其他网页提交配置"); return; }
            int size; try { size=Integer.parseInt(value(headers,"content-length", "-1")); } catch (NumberFormatException ex) { size=-1; }
            if (size<1 || size>(sourceSubmission!=null?131072:4096)) { reply(out,413,"配置长度无效"); return; }
            byte[] body=new byte[size]; int count=0,n;
            while (count<size && (n=in.read(body,count,size-count))!=-1) count+=n;
            if (count!=size) { reply(out,400,"配置未完整接收"); return; }
            Map<String,String> fields=new HashMap<>();
            try { for (String pair:new String(body,StandardCharsets.UTF_8).split("&")) {
                String[] parts=pair.split("=",2); String name=URLDecoder.decode(parts[0],"UTF-8");
                if (fields.put(name,parts.length==2?URLDecoder.decode(parts[1],"UTF-8"):"")!=null) { reply(out,400,"重复配置项"); return; }
            }
            } catch (IllegalArgumentException ex) { reply(out,400,"配置编码无效，请重新填写"); return; }
            if(sourceSubmission!=null) {
                String config=value(fields,"config","").trim();
                if(config.isEmpty()){reply(out,400,"请输入订阅地址或 JSON");return;}
                if(!used.compareAndSet(false,true)){reply(out,410,"配置已提交");return;}
                reply(out,202,"<h1>已发送</h1><p>请在电视上确认导入。</p>");
                if(!closed)sourceSubmission.accept(config);return;
            }
            URI nas;
            try { nas=NasAddress.parse(value(fields,"nas", "")); }
            catch (IllegalArgumentException ex) { reply(out,400,"NAS 地址应为 http(s)://主机:端口，不能包含路径、密码或 localhost。请返回重填。"); return; }
            String password=value(fields,"password", "");
            if (password.isEmpty() || password.length()>512) { reply(out,400,"请输入 1–512 字的 NAS 密码，请返回重填。"); return; }
            if (!used.compareAndSet(false,true)) { reply(out,410,"配置已提交"); return; }
            reply(out,202,"<h1>已发送到电视</h1><p>请在电视上确认连接并保存。验证成功后，以后开机将自动登录。</p><p>可以关闭此页面。</p>");
            if (!closed) submission.accept(nas,password);
        } catch (Exception ignored) { /* Never log submitted secrets or token URLs. */ }
        finally { connections.remove(socket); }
    }
    private static String value(Map<String,String> map,String key,String fallback) { String value=map.get(key); return value==null ? fallback : value; }
    private String form() {
        if(sourceSubmission!=null) return "<h1>添加视频来源</h1><p>填写订阅地址或 sources JSON，然后在电视确认。</p><form method=post action=\"/"+token+"/config\"><textarea name=config required rows=8 style=\"width:100%;font:inherit\" maxlength=100000></textarea><button>发送到电视</button></form>";

        return "<h1>连接你的 NAS</h1><p>在手机上填写，然后在电视上确认。</p><form method=post action=\"/"+token+"/config\" autocomplete=off>"
            +"<label for=nas>NAS 地址</label><input id=nas name=nas type=url required maxlength=240 placeholder=\"http://192.168.1.10:8899/\" value=\""+escape(defaultNas)+"\" autocapitalize=off spellcheck=false>"
            +"<label for=password>访问密码</label><input id=password name=password type=password required maxlength=512 autocomplete=off>"
            +"<p>连接成功后，密码将加密保存在这台电视上，用于自动登录。手机页面不会保存密码。</p><button type=submit>发送到电视</button></form>";
    }
    private static String escape(String value) { return value.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;"); }
    private static void reply(OutputStream out,int status,String text) throws IOException {
        String html="<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>LibreTV 手机配置</title>"
            +"<style>body{margin:0;background:#101922;color:#e4eef6;font:17px system-ui,sans-serif;padding:28px 20px}main{max-width:480px;margin:5vh auto}h1{font-size:30px}p{line-height:1.7;color:#afc1ce}label{display:block;margin:24px 0 9px}input,button{box-sizing:border-box;width:100%;padding:15px;border-radius:10px;font:inherit;border:1px solid #48606e}input{background:#233543;color:white}button{margin-top:18px;background:#6be2c3;color:#102b27;font-weight:700}input:focus{outline:2px solid #6be2c3}</style><main>"+text+"</main></html>";
        byte[] data=html.getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+status+" Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+data.length
            +"\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\nX-Content-Type-Options: nosniff\r\n"
            +"Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(data); out.flush();
    }
    @Override public void close() {
        closed=true;
        try { listener.close(); } catch (IOException ignored) { }
        synchronized (connections) { for (Socket socket:connections) try { socket.close(); } catch (IOException ignored) { } connections.clear(); }
        workers.shutdownNow();
    }
}
