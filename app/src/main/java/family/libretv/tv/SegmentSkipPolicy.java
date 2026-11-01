package family.libretv.tv;

import java.util.Locale;

/** Content-time rules shared by touch and remote playback. */
final class SegmentSkipPolicy {
    static String titleKey(String title) {
        return title == null ? "" : title.replaceAll("[\\s\\u3000]+", "").toLowerCase(Locale.ROOT);
    }

    static boolean valid(long intro, long outro, long duration) {
        return duration > 0 && intro >= 0 && outro >= 0 && intro < duration
                && outro < duration - intro && duration - intro - outro >= 1000;
    }

    static long introTarget(long position, long intro, long outro, long duration) {
        return valid(intro, outro, duration) && position >= 0 && position < intro ? intro : -1;
    }

    static boolean inOutro(long position, long intro, long outro, long duration) {
        return outro > 0 && valid(intro, outro, duration) && position >= duration - outro;
    }
}
