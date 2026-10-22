package family.libretv.tv;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class M3uPlaylistTest {
    @Test public void parsesBomQuotedCommasGroupAndVlcHeaders()throws Exception {
        List<M3uPlaylist.Channel> parsed=M3uPlaylist.parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id=\"id1\" tvg-logo=\"https://example.invalid/logo.png\" group-title=\"News, world\",Channel, one\r\n#EXTVLCOPT:http-user-agent=FixtureAgent\r\n#EXTVLCOPT:http-referrer=https://example.invalid/\r\nhttps://example.invalid/live?token=abc\r\n");
        assertEquals(1,parsed.size());assertEquals("Channel, one",parsed.get(0).name);assertEquals("News, world",parsed.get(0).group);assertEquals("FixtureAgent",parsed.get(0).userAgent);assertEquals("https://example.invalid/",parsed.get(0).referer);
    }
    @Test public void skipsUpdatePlaceholderAndUnsupportedProtocols()throws Exception {
        List<M3uPlaylist.Channel> p=M3uPlaylist.parse("#EXTM3U\n#EXTINF:-1 group-title=\"更新时间\",2026-10-22\nhttps://example.invalid/date.mp4\n#EXTINF:-1,Multicast\nudp://239.1.2.3:1234\n#EXTINF:-1,Real\nhttps://example.invalid/live.m3u8\n");assertEquals(1,p.size());assertEquals("Real",p.get(0).name);
    }
    @Test public void rejectsEmptyAndHlsMediaManifests()throws Exception {
        for(String text:new String[]{"#EXTM3U\n","<html>error</html>","#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttps://example.invalid/seg.ts"}){
            try{M3uPlaylist.parse(text);fail("Invalid directory accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void doesNotReuseMetadataAfterUnsupportedLine()throws Exception {
        List<M3uPlaylist.Channel> p=M3uPlaylist.parse("#EXTM3U\n#EXTINF:-1 tvg-id='a',Skip\nrtmp://example.invalid/live\nhttps://example.invalid/should-not-be-channel\n#EXTINF:-1,Valid\n#EXTGRP:Local\nhttp://[2001:db8::1]/live.m3u8\n");assertEquals(1,p.size());assertEquals("Local",p.get(0).group);
    }
}
