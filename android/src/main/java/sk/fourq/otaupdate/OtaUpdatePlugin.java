package sk.fourq.otaupdate;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import androidx.core.content.FileProvider;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * OtaUpdatePlugin
 */
@TargetApi(Build.VERSION_CODES.M)
public class OtaUpdatePlugin implements EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener {

    enum OtaStatus {
        DOWNLOADING, INSTALLING, ALREADY_RUNNING_ERROR, PERMISSION_NOT_GRANTED_ERROR, INTERNAL_ERROR
    }

    private final Registrar registrar;
    private EventChannel.EventSink progressSink;
    private String downloadUrl;
    private String androidProviderAuthority = "sk.fourq.ota_update.provider"; //FALLBACK provider authority
    private static final String TAG = "FLUTTER OTA";
    private Handler handler;
    private Context context;

    private OtaUpdatePlugin(Registrar registrar) {
        this.registrar = registrar;
        context = (registrar.activity() != null) ? registrar.activity() : registrar.context();
        handler = new Handler(context.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (progressSink != null) {
                    progressSink.success(Arrays.asList("" + OtaStatus.DOWNLOADING.ordinal(), "" + ((msg.arg1 * 100) / msg.arg2)));
                }
            }
        };
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        OtaUpdatePlugin plugin = new OtaUpdatePlugin(registrar);
        final EventChannel progressChannel = new EventChannel(registrar.messenger(), "sk.fourq.ota_update");
        progressChannel.setStreamHandler(plugin);
        registrar.addRequestPermissionsResultListener(plugin);
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (progressSink != null) {
            progressSink.error("" + OtaStatus.ALREADY_RUNNING_ERROR.ordinal(), "Method call was cancelled. One method call is already running", null);
        }
        progressSink = events;
        downloadUrl = ((Map) arguments).get("url").toString();

        // user-provided provider authority
        Object authority = ((Map) arguments).get("androidProviderAuthority");
        if (authority != null) {
            androidProviderAuthority = authority.toString();
        } else {
            androidProviderAuthority = context.getPackageName() + "." + "ota_update_provider";
        }

//        if (
////                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(registrar.context(), Manifest.permission.ACCESS_WIFI_STATE) &&
////                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(registrar.context(), Manifest.permission.ACCESS_NETWORK_STATE) &&
//                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(registrar.context(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//
//        } else {
//            String[] permissions = {
////                    Manifest.permission.ACCESS_WIFI_STATE,
////                    Manifest.permission.ACCESS_NETWORK_STATE,
//                    Manifest.permission.WRITE_EXTERNAL_STORAGE
//            };
//            ActivityCompat.requestPermissions(registrar.activity(), permissions, 0);
//        }
        handleCall();
    }

    @Override
    public void onCancel(Object o) {
        progressSink = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode == 0 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                    progressSink = null;
                    return false;
                }
            }
            handleCall();
            return true;
        } else {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                progressSink = null;
            }
            return false;
        }
    }

    private void handleCall() {
        try {
            //PREPARE URLS
            Log.e(TAG, "handleCall");
            final File destinationFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ordo.apk");

            //DELETE APK FILE IF SOME ALREADY EXISTS
            if (destinationFile.exists()) {
                if (!destinationFile.delete()) {
                    Log.e(TAG, "ERROR: unable to delete old apk file before starting OTA");
                }
            }

            final long downloadId = downloadSimple(downloadUrl, "ordo.apk", "正在下载中……");

            //START TRACKING DOWNLOAD PROGRESS IN SEPARATE THREAD

            //REGISTER LISTENER TO KNOW WHEN DOWNLOAD IS COMPLETE
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    Log.e("downloadComplete", i.toString() + i.getExtras().toString());
                    for (String key : i.getExtras().keySet()) {
                        Log.e(key, i.getExtras().get(key).toString());
                    }
                    //DOWNLOAD IS COMPLETE, UNREGISTER RECEIVER AND CLOSE PROGRESS SINK
                    context.unregisterReceiver(this);
                    installAPK(destinationFile);
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.INTERNAL_ERROR.ordinal(), e.getMessage(), null);
                progressSink = null;
            }
            Log.e(TAG, "ERROR: " + e.getMessage(), e);
        }
    }

    private void trackDownloadProgress(final long downloadId, final DownloadManager manager) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                //REPORT PROGRESS WHILE DOWNLOAD STILL RUNS
                boolean downloading = true;
                while (downloading) {
                    //QUERY CURRENT PROGRESS STATUS
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById(downloadId);
                    Cursor c = manager.query(q);
                    c.moveToFirst();
                    //PUSH THE STATUS THROUGH THE SINK
                    int bytes_downloaded = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int bytes_total = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (progressSink != null && bytes_total > 0) {
                        Message message = new Message();
                        message.arg1 = bytes_downloaded;
                        message.arg2 = bytes_total;
                        handler.sendMessage(message);
                    }
                    //STOP CYCLE IF DOWNLOAD IS COMPLETE
                    if (c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false;
                    }
                    //CLOSE CURSOR
                    c.close();
                    //WAIT FOR 1/4 SECOND FOR ANOTHER ITERATION
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    //简单的下载功能
    public long downloadSimple(String url, String title, String desc) {
        Uri uri = Uri.parse(url);
        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setAllowedOverRoaming(true);
        req.setShowRunningNotification(true);
        req.setVisibleInDownloadsUi(true);
        //下载中和下载完后都显示通知栏
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE | DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        //使用系统默认的下载路径 此处为应用内 /android/data/packages ,所以兼容7.0
        req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, title);
        //通知栏标题
        req.setTitle(title);
        //通知栏描述信息
        req.setDescription(desc);
        //设置类型为.apk
        req.setMimeType("application/vnd.android.package-archive");
        //获取下载任务ID
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = dm.enqueue(req);
        trackDownloadProgress(id, dm);
//        if (context != null) {
//            context.startActivity(new android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));//启动系统下载界面
//        }
        return id;
    }

    private void installAPK(File desFile) {
        setPermission(desFile.getAbsolutePath());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        // 由于没有在Activity环境下启动Activity,设置下面的标签
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        //Android 7.0以上要使用FileProvider
        if (Build.VERSION.SDK_INT >= 24) {
            //参数1 上下文, 参数2 Provider主机地址 和配置文件中保持一致   参数3  共享的文件
            Uri apkUri = FileProvider.getUriForFile(context, androidProviderAuthority, desFile);
            //添加这一句表示对目标应用临时授权该Uri所代表的文件
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        } else {
            intent.setDataAndType(Uri.fromFile(desFile), "application/vnd.android.package-archive");
        }
        context.startActivity(intent);
    }

    //修改文件权限
    private void setPermission(String absolutePath) {
        String command = "chmod " + "777" + " " + absolutePath;
        Runtime runtime = Runtime.getRuntime();
        try {
            runtime.exec(command);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
