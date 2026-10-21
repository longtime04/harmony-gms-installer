package com.muse.gmssetup;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 鸿蒙 4.2 谷歌服务安装助手：复刻 FeiVPN 的 9 步修复向导。
 * 能自动检测的步骤自动打勾，能代劳的（写备份数据、装包）一键完成，
 * 必须用户在系统界面里点的步骤给跳转 + 图文说明。
 */
public class MainActivity extends Activity {

    // ---- 目标包名 ----
    static final String PKG_GSF = "com.google.android.gsf";
    static final String PKG_GMS = "com.google.android.gms";
    static final String PKG_VENDING = "com.android.vending";
    static final String PKG_HELPER = "com.lzplay.helper";
    static final String[] PKG_BACKUP_CANDIDATES = {
            "com.huawei.localBackup", "com.huawei.KoBackup",
            "com.huawei.kobackup", "com.huawei.backup"};

    static final String BACKUP_PASSWORD = "eloygomeztv2020";
    static final String ACTION_INSTALL_RESULT = "com.muse.gmssetup.INSTALL_RESULT";

    private LinearLayout stepContainer;
    private final List<Step> steps = new ArrayList<>();

    private static class Step {
        String title, desc, btnText;
        Runnable action;
        java.util.concurrent.Callable<String> status; // 返回 null=无法判定 / ""之外的文字=状态说明；以 ✓ 开头视为完成
        Button btn;
        TextView statusView;
    }

    // ================= 生命周期 =================

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        buildSteps();
        handleInstallResult(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallResult(intent);
    }

    // ================= 界面 =================

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable cardBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 246, 248));

        TextView header = new TextView(this);
        header.setText("谷歌服务安装助手（鸿蒙 4.2）");
        header.setTextSize(20);
        header.setTextColor(Color.WHITE);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(20), dp(18), dp(6));
        header.setBackgroundColor(Color.rgb(30, 136, 229));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("按顺序一步步来，每步做完会自动打勾。标「去操作」的是要你在系统界面里点几下，跟着说明走就行。");
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(225, 240, 255));
        sub.setPadding(dp(18), 0, dp(18), dp(16));
        sub.setBackgroundColor(Color.rgb(30, 136, 229));
        root.addView(sub);

        ScrollView scroll = new ScrollView(this);
        stepContainer = new LinearLayout(this);
        stepContainer.setOrientation(LinearLayout.VERTICAL);
        stepContainer.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(stepContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView ghLink = new TextView(this);
        ghLink.setText("作者 GitHub 主页：github.com/longtime04（点这里打开）");
        ghLink.setTextSize(13);
        ghLink.setTextColor(Color.rgb(30, 136, 229));
        ghLink.setGravity(Gravity.CENTER);
        ghLink.setPadding(dp(12), dp(10), dp(12), dp(12));
        ghLink.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/longtime04")));
            } catch (Exception e) {
                Toast.makeText(this, "打不开浏览器，请手动访问 github.com/longtime04",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(ghLink);

        setContentView(root);
    }

    private void addStepCard(int index, Step s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(Color.WHITE));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText((index + 1) + ". " + s.title);
        title.setTextSize(16);
        title.setTextColor(Color.rgb(33, 33, 33));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(s.desc);
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(97, 97, 97));
        desc.setPadding(0, dp(4), 0, dp(8));
        card.addView(desc);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        s.statusView = new TextView(this);
        s.statusView.setTextSize(13);
        s.statusView.setTextColor(Color.rgb(158, 158, 158));
        s.statusView.setText("检测中…");
        row.addView(s.statusView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        s.btn = new Button(this);
        s.btn.setText(s.btnText);
        s.btn.setTextSize(14);
        s.btn.setOnClickListener(v -> {
            try { s.action.run(); } catch (Exception e) {
                toast("操作失败：" + e.getMessage());
            }
            refreshStatuses();
        });
        row.addView(s.btn);
        card.addView(row);

        stepContainer.addView(card);
    }

    private void refreshStatuses() {
        for (Step s : steps) {
            if (s.statusView == null) continue;
            String st;
            try { st = s.status.call(); } catch (Exception e) { st = null; }
            if (st == null) {
                s.statusView.setText("按说明操作");
                s.statusView.setTextColor(Color.rgb(158, 158, 158));
            } else if (st.startsWith("✓")) {
                s.statusView.setText(st);
                s.statusView.setTextColor(Color.rgb(46, 160, 67));
            } else {
                s.statusView.setText(st);
                s.statusView.setTextColor(Color.rgb(230, 140, 0));
            }
        }
    }

    private Step mkStep(String title, String desc, String btn, Runnable a,
                        java.util.concurrent.Callable<String> st) {
        Step s = new Step();
        s.title = title; s.desc = desc; s.btnText = btn; s.action = a; s.status = st;
        steps.add(s);
        return s;
    }

    // ================= 步骤定义 =================

    private void buildSteps() {
        // 准备：本 App 自身权限
        mkStep("准备：给本助手开权限",
                "需要两项：①允许安装应用 ②允许访问全部文件（用来写入备份数据）。点按钮逐个开好。",
                "去授权",
                () -> {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } else if (!Environment.isExternalStorageManager()) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } else {
                        toast("权限已齐");
                    }
                },
                () -> {
                    boolean a = getPackageManager().canRequestPackageInstalls();
                    boolean b = Environment.isExternalStorageManager();
                    return (a && b) ? "✓ 权限已齐" : "未完成：安装权限" + (a ? "✓" : "✗") + " 文件权限" + (b ? "✓" : "✗");
                });

        // 1. 关闭自动日期，调到 2018
        mkStep("关闭自动日期，日期调到2018年",
                "打开日期设置后：关掉「自动设置日期和时间」，把年份改到 2018 年（月份日期随意）。这是为了让老备份能恢复出来。",
                "去操作",
                () -> startActivity(new Intent(Settings.ACTION_DATE_SETTINGS)),
                () -> autoTime() == 0 ? "✓ 已关闭自动（请确认年份是2018）" : "自动日期还开着");

        // 2. 给“备份”App 全部权限
        mkStep("给「备份」App 开全部权限",
                "打开备份 App 的权限页，把权限全部打开（包括「其他权限」里的）。找不到备份 App 的机型可手动去：设置→应用→备份→权限。",
                "去操作",
                () -> {
                    String pkg = findBackupPkg();
                    if (pkg != null) openAppDetails(pkg);
                    else toast("本机没找到华为「备份」App，非华为机型可跳过本步");
                },
                () -> {
                    String pkg = findBackupPkg();
                    if (pkg == null) return null;
                    // v1.1: 只查关键存储权限。v1.0 查全部权限时，备份 App 清单里的
                    // 系统级权限永远显示未授予，导致肉眼全开也误报"未全开"
                    return backupPermsOk(pkg) ? "✓ 备份权限已全开" : "备份权限未全开";
                });

        // 3. 写入备份数据 + 去恢复
        mkStep("运行备份，恢复谷歌服务助手",
                "先点「写入数据」把备份文件放到位；再点按钮打开备份 App：右上角 ⋮ →「从内部存储恢复」→选 2020年4月2日那条→全选→恢复，密码：" + BACKUP_PASSWORD + "（点按钮时会自动复制密码）。",
                "写入数据",
                () -> {
                    if (!Environment.isExternalStorageManager()) {
                        toast("先完成第 1 步（准备）的文件权限授权");
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                        return;
                    }
                    new Thread(() -> {
                        boolean ok = extractBackupData();
                        runOnUiThread(() -> {
                            if (ok) {
                                copyText(BACKUP_PASSWORD);
                                toast("备份数据已写入，密码已复制。打开备份去恢复");
                                String pkg = findBackupPkg();
                                if (pkg != null) launchApp(pkg);
                                refreshStatuses();
                            } else {
                                toast("写入失败，检查文件权限是否开好");
                            }
                        });
                    }).start();
                },
                () -> isInstalled(PKG_HELPER) ? "✓ 谷歌服务助手已恢复" :
                        (backupDataExists() ? "备份数据已就位，去备份App里恢复" : "先点「写入数据」"));

        // 4. 打开谷歌助手，激活设备管理员
        mkStep("打开谷歌服务助手，激活/允许权限",
                "打开谷歌服务助手 → 激活设备管理器：出现安全提醒时勾选「我已知晓」，等倒计时结束点「仍要激活」。激活后回到本助手。",
                "去操作",
                () -> {
                    if (!launchApp(PKG_HELPER)) toast("还没检测到谷歌服务助手，先完成上一步恢复");
                },
                () -> {
                    if (!isInstalled(PKG_HELPER)) return "谷歌服务助手未安装";
                    return helperAdminActive() ? "✓ 助手已激活" : "已恢复，待激活设备管理器";
                });

        // 5. 日期改回自动
        mkStep("日期时间，改回自动设置",
                "把「自动设置日期和时间」重新打开，时间恢复正常。",
                "去操作",
                () -> startActivity(new Intent(Settings.ACTION_DATE_SETTINGS)),
                () -> autoTime() == 1 ? "✓ 已恢复自动" : "还没改回自动");

        // 6. 安装 Play 框架（GSF Proxy）
        mkStep("安装 Play 框架",
                "安装 microG GSF Proxy（小文件，秒装）。弹安装确认窗时点「安装」。",
                "安装",
                () -> installAsset("gsf.apk"),
                () -> isInstalled(PKG_GSF) ? "✓ 已安装" : "未安装");

        // 7. 安装 Play 服务（microG 华为版）
        mkStep("安装 Play 服务（microG）",
                "安装 microG 华为专版（约 93MB，稍等）。弹安装确认窗时点「安装」。",
                "安装",
                () -> installAsset("gms.apk"),
                () -> isInstalled(PKG_GMS) ? "✓ 已安装" : "未安装");

        // 8. 给 Play 服务开全部权限
        mkStep("打开 Play 服务（microG）所有权限",
                "打开 microG 的权限页：权限全部允许；再去 电池→选「不受限制」（在权限页点应用名上方的电池选项，或去设置→电池→应用启动管理里关掉自动管理）。",
                "去操作",
                () -> openAppDetails(PKG_GMS),
                () -> {
                    if (!isInstalled(PKG_GMS)) return "Play服务未安装";
                    return allPermsGranted(PKG_GMS) ? "✓ 权限已全开" : "权限未全开";
                });

        // 9. 安装谷歌商店并打开
        mkStep("安装谷歌商店、连接外网、运行",
                "先装商店（约 100MB）。装好后：连上外网（开梯子），再打开 Play 商店登录谷歌账号。第一次打开慢是正常的。",
                "安装",
                () -> {
                    if (!isInstalled(PKG_VENDING)) installAsset("vending.apk");
                    else if (!launchApp(PKG_VENDING)) toast("商店已装，手动点桌面图标打开");
                },
                () -> isInstalled(PKG_VENDING) ? "✓ 已安装，装好后本按钮变为打开商店" : "未安装");

        for (int i = 0; i < steps.size(); i++) addStepCard(i, steps.get(i));
        refreshStatuses();
    }

    // ================= 工具方法 =================

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("backup", s));
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean allPermsGranted(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return true;
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                int flag = pi.requestedPermissionsFlags[i];
                if ((flag & PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) {
                    // 只看危险权限的授予状态：普通权限安装即授予，flags 已覆盖
                    if (getPackageManager().checkPermission(pi.requestedPermissions[i], pkg)
                            != PackageManager.PERMISSION_GRANTED) return false;
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    // v1.1 新增：备份 App 的权限检测只看关键存储权限。
    // 备份恢复只需要从内部存储读备份数据；MANAGE_EXTERNAL_STORAGE 或
    // READ_EXTERNAL_STORAGE 任一已授予即算过。若备份 App 压根没声明存储
    // 权限（走系统特权通道），则无法判定，不拦路——以用户肉眼为准。
    private boolean backupPermsOk(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return true;
            boolean declaresManage = false, declaresRead = false;
            for (String p : pi.requestedPermissions) {
                if ("android.permission.MANAGE_EXTERNAL_STORAGE".equals(p)) declaresManage = true;
                if ("android.permission.READ_EXTERNAL_STORAGE".equals(p)) declaresRead = true;
            }
            if (declaresManage && pm.checkPermission(
                    "android.permission.MANAGE_EXTERNAL_STORAGE", pkg)
                    == PackageManager.PERMISSION_GRANTED) return true;
            if (declaresRead && pm.checkPermission(
                    "android.permission.READ_EXTERNAL_STORAGE", pkg)
                    == PackageManager.PERMISSION_GRANTED) return true;
            return !declaresManage && !declaresRead;
        } catch (Exception e) { return false; }
    }

    private int autoTime() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.AUTO_TIME, 1); }
        catch (Exception e) { return 1; }
    }

    private String findBackupPkg() {
        for (String p : PKG_BACKUP_CANDIDATES) if (isInstalled(p)) return p;
        return null;
    }

    private boolean helperAdminActive() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            List<ComponentName> admins = dpm.getActiveAdmins();
            if (admins == null) return false;
            for (ComponentName c : admins) if (PKG_HELPER.equals(c.getPackageName())) return true;
            return false;
        } catch (Exception e) { return false; }
    }

    private boolean launchApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    private void openAppDetails(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            startActivity(i);
        } catch (Exception e) { toast("打开失败，手动去设置→应用里找"); }
    }

    private boolean backupDataExists() {
        File f = new File(Environment.getExternalStorageDirectory(),
                "Huawei/Backup/backupFiles/2020-07-24_11-03-54-199/info.xml");
        return f.exists();
    }

    private boolean extractBackupData() {
        try {
            File root = Environment.getExternalStorageDirectory();
            try (InputStream in = getAssets().open("payload/backup.zip");
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry e;
                byte[] buf = new byte[65536];
                while ((e = zis.getNextEntry()) != null) {
                    File out = new File(root, e.getName());
                    if (e.isDirectory()) { out.mkdirs(); continue; }
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n; while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
            }
            return backupDataExists();
        } catch (Exception e) { return false; }
    }

    // ================= 安装管线 =================

    private File assetToCache(String assetName) throws Exception {
        File dir = new File(getCacheDir(), "payload_out");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, assetName);
        try (InputStream in = getAssets().open("payload/" + assetName);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[262144];
            int n; while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    private void installAsset(String assetName) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("先给本助手开「安装应用」权限");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        toast("正在准备安装包…");
        new Thread(() -> {
            try {
                File apk = assetToCache(assetName);
                runOnUiThread(() -> {
                    if (!installViaSession(apk)) installViaIntent(apk);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("准备失败：" + e.getMessage()));
            }
        }).start();
    }

    private boolean installViaSession(File apk) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = pi.createSession(params);
            session = pi.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("pkg", 0, apk.length())) {
                byte[] buf = new byte[262144];
                int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent intent = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_RESULT);
            PendingIntent pending = PendingIntent.getActivity(this, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            session.close();
            return true;
        } catch (Exception e) {
            try { if (session != null) session.abandon(); } catch (Exception ignored) {}
            return false;
        }
    }

    private void installViaIntent(File apk) {
        try {
            Uri uri = Uri.parse("content://com.muse.gmssetup.fileprovider/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("拉起安装器失败：" + e.getMessage());
        }
    }

    private void handleInstallResult(Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            toast("安装完成");
            refreshStatuses();
        } else if (status != -999) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast("安装未完成（状态 " + status + "）" + (msg != null ? "：" + msg : ""));
            refreshStatuses();
        }
    }
}
