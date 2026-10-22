package family.libretv.tv;

import java.net.URI;

/** Only public upstream media URLs may leave the authenticated NAS client. */
final class CastUrl {
    static String direct(String value, URI nas) {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException e) { throw new IllegalArgumentException("播放地址格式无效"); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("投屏需要 HTTP(S) 视频直链");
        }
        if (NasAddress.sameOrigin(nas, uri)) {
            throw new IllegalArgumentException("此地址需要 NAS 登录授权，当前直连投屏不支持，请选择其他来源");
        }
        return uri.toString();
    }
    private CastUrl() { }
}
