package family.libretv.tv;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.*;

/** Android 6's stock installer cannot consume content URIs; sessions work from API 21. */
final class UpdateInstaller {
    private UpdateInstaller() { }
    static void install(Context context, File apk) throws Exception {
        if (Build.VERSION.SDK_INT >= 24) {
            context.startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(FileProvider.getUriForFile(context, context.getPackageName()+".updates", apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName()); params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk",0,apk.length())) {
                byte[] buffer = new byte[32768]; int n;
                while ((n=in.read(buffer)) != -1) out.write(buffer,0,n);
                session.fsync(out);
            }
            Intent callback = new Intent(context, UpdateInstallReceiver.class).setAction(context.getPackageName()+".INSTALL_RESULT");
            PendingIntent pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pending.getIntentSender());
        } catch (Exception ex) { installer.abandonSession(id); throw ex; }
    }
}
