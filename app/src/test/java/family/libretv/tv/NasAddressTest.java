package family.libretv.tv;
import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;
public class NasAddressTest {
    @Test public void acceptsLanAndNormalizes() {
        assertEquals("http://192.0.2.10:8899/", NasAddress.parse(" http://192.0.2.10:8899 ").toString());
        assertTrue(NasAddress.sameOrigin(NasAddress.parse("https://nas.example"), URI.create("https://nas.example:443/api/auth")));
    }
    @Test public void rejectsCredentialAndAmbiguousAddresses() {
        for (String s : new String[]{"file:///etc/passwd", "http://user:password@nas.example", "http://localhost:18899", "http://127.0.0.1", "http://[::1]", "http://nas.example/path", "http://nas.example?password=x", "http://nas.example#x", "http://nas.example:99999", "http://nas.example:0", "not a url"}) {
            try { NasAddress.parse(s); fail("accepted: " + s); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void neverSharesSessionWithOtherOrigin() {
        URI nas = NasAddress.parse("http://192.0.2.10:8899");
        for (String s : new String[]{"http://192.0.2.10:8900/api/proxy", "https://192.0.2.10:8899/api/proxy", "http://evil.example/api/proxy", "http://x@192.0.2.10:8899/api/proxy"}) assertFalse(NasAddress.sameOrigin(nas, URI.create(s)));
    }
}
