package family.libretv.tv;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class PhonePairingServerTest {
    private String request(PhonePairingServer server,String method,String path,String extra,String body) throws Exception {
        try (Socket socket=new Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(3000);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String header=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\n"+extra+"Content-Length: "+bytes.length+"\r\n\r\n";
            // Send a complete HTTP request before reading an early rejection. Separate
            // unbuffered header/body writes race a server that closes after rejecting headers.
            ByteArrayOutputStream payload=new ByteArrayOutputStream();
            payload.write(header.getBytes(StandardCharsets.US_ASCII));payload.write(bytes);
            socket.getOutputStream().write(payload.toByteArray());socket.getOutputStream().flush();
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096];int n;
            while ((n=socket.getInputStream().read(buffer))!=-1) {
                out.write(buffer,0,n);
                String received=new String(out.toByteArray(),StandardCharsets.ISO_8859_1);
                int end=received.indexOf("\r\n\r\n");
                if (end>=0) {
                    java.util.regex.Matcher length=java.util.regex.Pattern.compile("Content-Length: ([0-9]+)").matcher(received.substring(0,end));
                    if (length.find() && out.size()>=end+4+Integer.parseInt(length.group(1))) break;
                }
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private String path(PhonePairingServer server) { return URI.create(server.url()).getPath(); }
    @Test public void formIsPrivateSingleUseAndDoesNotReflectPassword() throws Exception {
        AtomicReference<String> submitted=new AtomicReference<>();
        try (PhonePairingServer server=new PhonePairingServer("127.0.0.1","http://nas.example:8899/",(origin,password)->submitted.set(origin+" "+password))) {
            String page=request(server,"GET",path(server),"","");
            assertTrue("Unexpected response: "+page.substring(0,Math.min(160,page.length())),page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store"));
            assertTrue(page.contains("type=password")); assertFalse(server.url().contains("nas.example"));
            assertTrue(request(server,"GET","/unknown","","").startsWith("HTTP/1.1 404"));
            String body="nas="+NasClient.encode("http://nas.example:8899/")+"&password="+NasClient.encode("fixture +&密码");
            String response=request(server,"POST",path(server)+"/config","Content-Type: application/x-www-form-urlencoded\r\n",body);
            assertTrue(response.startsWith("HTTP/1.1 202")); assertFalse(response.contains("fixture +"));
            for (int i=0;i<50 && submitted.get()==null;i++) Thread.sleep(10);
            assertEquals("http://nas.example:8899/ fixture +&密码",submitted.get());
            assertTrue(request(server,"GET",path(server),"","").startsWith("HTTP/1.1 410"));
            assertTrue(request(server,"POST",path(server)+"/config","Content-Type: application/x-www-form-urlencoded\r\n",body).startsWith("HTTP/1.1 410"));
        }
    }
    @Test public void rejectsCrossOriginInvalidNasAndOversize() throws Exception {
        try (PhonePairingServer server=new PhonePairingServer("127.0.0.1","",(origin,password)->fail("invalid submission accepted"))) {
            String target=path(server)+"/config", body="nas=http%3A%2F%2Fnas.example&password=fixture-only";
            assertTrue(request(server,"POST",target,"Content-Type: application/x-www-form-urlencoded\r\nOrigin: https://foreign.example\r\n",body).startsWith("HTTP/1.1 403"));
            assertTrue(request(server,"POST",target,"Content-Type: application/json\r\n",body).startsWith("HTTP/1.1 415"));
            assertTrue(request(server,"POST",target,"Content-Type: application/x-www-form-urlencoded\r\n","nas=http%3A%2F%2Flocalhost&password=fixture-only").startsWith("HTTP/1.1 400"));
            assertTrue(request(server,"POST",target,"Content-Type: application/x-www-form-urlencoded\r\n",new String(new char[4097]).replace('\0','x')).startsWith("HTTP/1.1 413"));
        }
    }
    @Test public void expiresAndClosesListener() throws Exception {
        PhonePairingServer server=new PhonePairingServer("127.0.0.1","",(origin,password)->fail("expired submission accepted"),1);
        int port=server.port(); Thread.sleep(10);
        assertTrue(request(server,"GET",path(server),"","").startsWith("HTTP/1.1 410")); server.close();
        try (Socket ignored=new Socket("127.0.0.1",port)) { fail("listener remains open"); } catch (ConnectException expected) { }
    }
    @Test public void sourceFormAcceptsSubscriptionWithoutNasPassword() throws Exception {
        AtomicReference<String> submitted=new AtomicReference<>();
        try(PhonePairingServer server=new PhonePairingServer("127.0.0.1",(PhonePairingServer.SourceSubmission)submitted::set)) {
            String form=request(server,"GET",path(server),"","");
            assertTrue(form.contains("name=config"));assertFalse(form.contains("type=password"));
            String value="https://config.example/sources.json";
            assertTrue(request(server,"POST",path(server)+"/config","Content-Type: application/x-www-form-urlencoded\r\n","config="+NasClient.encode(value)).startsWith("HTTP/1.1 202"));
            for(int n=0;n<50 && submitted.get()==null;n++)Thread.sleep(10);
            assertEquals(value,submitted.get());
            assertTrue(request(server,"GET",path(server),"","").startsWith("HTTP/1.1 410"));
        }
    }
}
