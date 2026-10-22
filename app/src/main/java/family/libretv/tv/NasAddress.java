package family.libretv.tv;

import java.net.URI;
import java.util.Locale;

/** Only an explicit HTTP(S) origin; never accepts embedded credentials or a tunnel localhost. */
public final class NasAddress {
    private NasAddress() {}
    public static URI parse(String value) {
        try {
            URI uri = new URI(value.trim());
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || "localhost".equalsIgnoreCase(host) || host.startsWith("127.")
                    || "[::1]".equals(host) || "0.0.0.0".equals(host)) {
                throw new IllegalArgumentException();
            }
            return new URI(scheme.toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT), uri.getPort(), "/", null, null);
        } catch (Exception ex) {
            throw new IllegalArgumentException("请输入 NAS 的 http(s)://主机:端口，不能含密码、路径或 localhost");
        }
    }
    private static int port(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
    public static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b)
                && b.getRawUserInfo() == null;
    }
}
