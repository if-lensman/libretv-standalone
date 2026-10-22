package family.libretv.tv;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException ex) { Toast.makeText(context,"无法打开安装确认，请在设置中重试更新。",Toast.LENGTH_LONG).show(); }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context,"更新未安装，可在设置中重新下载安装。",Toast.LENGTH_LONG).show();
        }
    }
}
