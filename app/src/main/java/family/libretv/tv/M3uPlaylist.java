package family.libretv.tv;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Channel-directory M3U parser; deliberately separate from an HLS media manifest. */
final class M3uPlaylist {
    static final int MAX_BYTES=4*1024*1024, MAX_CHANNELS=5000;
    static final class Channel {
        final String name, group, tvgId, logo, url, userAgent, referer;
        Channel(String name,String group,String id,String logo,String url,String ua,String ref) {
            this.name=name;this.group=group;this.tvgId=id;this.logo=logo;this.url=url;this.userAgent=ua;this.referer=ref;
        }
    }
    private static final Pattern ATTRIBUTE=Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    static List<Channel> parse(String text) throws IOException {
        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("频道列表过大，请使用精简订阅");
        String data=text.replace("\uFEFF","").trim();
        if(!data.startsWith("#EXTM3U"))throw new IOException("请导入 M3U 频道列表");
        List<Channel> channels=new ArrayList<>();Map<String,String> attrs=new HashMap<>();
        String name="",group="",ua="",ref="";boolean pending=false;
        for(String raw:data.split("\\r?\\n")) {
            String line=raw.trim();if(line.length()>16384)throw new IOException("频道记录过长");
            if(line.startsWith("#EXT-X-TARGETDURATION")||line.startsWith("#EXT-X-STREAM-INF"))throw new IOException("这是视频播放地址，请导入包含频道的 M3U 列表");
            if(line.startsWith("#EXTINF:")) {
                attrs=new HashMap<>();Matcher m=ATTRIBUTE.matcher(line);
                while(m.find())attrs.put(m.group(1).toLowerCase(Locale.ROOT),m.group(2)!=null?m.group(2):m.group(3));
                int comma=-1;char quote=0;
                for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='\''||c=='\"'){if(quote==0)quote=c;else if(quote==c)quote=0;}else if(c==','&&quote==0){comma=i;break;}}
                name=comma>=0?line.substring(comma+1).trim():attrs.getOrDefault("tvg-name","");
                if(name.isEmpty())name=attrs.getOrDefault("tvg-name","");
                group=attrs.getOrDefault("group-title","未分组");ua="";ref="";pending=true;
            } else if(pending && line.startsWith("#EXTGRP:"))group=line.substring(8).trim();
            else if(pending && line.startsWith("#EXTVLCOPT:http-user-agent="))ua=line.substring(line.indexOf('=')+1).trim();
            else if(pending && (line.startsWith("#EXTVLCOPT:http-referrer=")||line.startsWith("#EXTVLCOPT:http-referer=")))ref=line.substring(line.indexOf('=')+1).trim();
            else if(!line.isEmpty()&&!line.startsWith("#")&&pending) {
                pending=false;
                if(name.isEmpty()||"更新时间".equals(group)||"更新时间".equals(attrs.get("tvg-id"))||name.matches("\\d{4}-\\d{2}-\\d{2}.*"))continue;
                if(!http(line))continue;
                if(!ua.isEmpty()&&(ua.contains("\r")||ua.contains("\n")))continue;
                if(!ref.isEmpty()&&!http(ref))continue;
                String logo=attrs.getOrDefault("tvg-logo","");if(!http(logo))logo="";
                channels.add(new Channel(name,group,attrs.getOrDefault("tvg-id",""),logo,line,ua,ref));
                if(channels.size()>MAX_CHANNELS)throw new IOException("频道超过5000条，请使用精简订阅");
            }
        }
        if(channels.isEmpty())throw new IOException("列表没有可用的 HTTP(S) 频道，已保留上次列表");
        return channels;
    }
    static boolean http(String url) {
        try{URI u=new URI(url);return ("http".equalsIgnoreCase(u.getScheme())||"https".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&u.getRawUserInfo()==null&&u.getFragment()==null;}
        catch(Exception e){return false;}
    }
}
