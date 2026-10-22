package family.libretv.tv;
import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;
public class CastUrlTest {
    private final URI nas = URI.create("http://nas.local:8899/");
    @Test public void retainsSignedMediaQuery() {
        String value = "https://cdn.example/master.m3u8?token=a%2Bb&expires=123";
        assertEquals(value, CastUrl.direct(value, nas));
    }
    @Test public void rejectsAuthenticatedNasAndNonMediaSchemes() {
        for(String value : new String[]{"http://nas.local:8899/api/proxy?url=x", "file:///system/x", "javascript:alert(1)", "https://user:secret@cdn.example/video", "https://cdn.example/video#fragment", "/relative"}) {
            try { CastUrl.direct(value, nas); fail("accepted " + value); } catch(IllegalArgumentException expected) { }
        }
    }
}
