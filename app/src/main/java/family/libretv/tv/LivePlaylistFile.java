package family.libretv.tv;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;

/** Import a picked document into the same bounded cache on phone and TV. */
final class LivePlaylistFile {
    private LivePlaylistFile() {}

    static void importInto(Context context, LiveRepository repository, Uri uri) throws Exception {
        String name = "本地频道";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String display = cursor.getString(0);
                if (display != null && !display.trim().isEmpty()) name = display;
            }
        }
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法读取文件");
        repository.importPlaylist(name, LocalRepository.read(input, M3uPlaylist.MAX_BYTES));
    }
}
