package com.muse.gmssetup;

import android.app.Activity;
import android.app.Dialog;
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
        // v1.3 新界面用
        int index;
        TextView medal;
        View dot;
        LinearLayout rowRoot;
    }

    // v1.3 界面组件
    private View headerArt;
    private View progFill;
    private FrameLayout progTrack;
    private TextView progText;
    private LinearLayout doneCard;
    private View topSpacer;
    private TextView doneTitle, doneSub;

    // v1.3 视觉常量（草稿 v3 定稿：墨绿 × 青玉 × 鎏金）
    static final int INK_DARK = Color.rgb(10, 33, 28);
    static final int TEAL = Color.rgb(14, 148, 136);
    static final int GOLD = Color.rgb(217, 169, 74);
    static final int GOLD_SOFT = Color.rgb(222, 198, 132);
    static final int PAPER = Color.rgb(245, 247, 245);
    static final int MUTED = Color.rgb(116, 133, 126);
    static final int TITLE_INK = Color.rgb(27, 43, 37);

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

    private GradientDrawable roundRect(int color, float radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private GradientDrawable ovalBg(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable tealBtnBg() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{TEAL, Color.rgb(20, 184, 166)});
        d.setCornerRadius(dp(11));
        return d;
    }

    private View makeBadge(View inner, String label, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(26, 255, 255, 255));
        bg.setStroke(dp(1), GOLD_SOFT);
        circle.setBackground(bg);
        circle.addView(inner, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        circle.setOnClickListener(click);
        col.addView(circle, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(9.5f);
        lab.setTextColor(Color.rgb(216, 197, 138));
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(0, dp(4), 0, 0);
        col.addView(lab);
        return col;
    }

    private void buildUi() {
        // 根布局：滚动层垫底、头部悬浮在上——往上滑时步骤从圆弧底下钻过去（v1.3.2 按真机反馈改）
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);

        // ===== 头部：圆弧 + 液态玻璃（草稿 v3） =====
        FrameLayout headerWrap = new FrameLayout(this);
        headerArt = new HeaderArt(this);
        // 注意：背景画不能直接 MATCH_PARENT——WRAP_CONTENT 的 FrameLayout 会被它撑成整屏高
        // （v1.3 首版实测：头部吞掉整屏、步骤面板被挤出屏幕）。先给 0 高，布局后按内容高度同步。
        headerWrap.addView(headerArt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        LinearLayout headerContent = new LinearLayout(this);
        headerContent.setOrientation(LinearLayout.VERTICAL);
        headerContent.setPadding(dp(20), dp(20), dp(20), dp(58));
        headerWrap.addView(headerContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("谷歌服务安装助手");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        title.getPaint().setFakeBoldText(true);
        titleCol.addView(title);
        TextView sub = new TextView(this);
        sub.setText("鸿蒙 4.2 及以下 · 按顺序装，装完打勾");
        sub.setTextSize(12.5f);
        sub.setTextColor(Color.rgb(183, 220, 208));
        sub.setPadding(0, dp(7), 0, 0);
        titleCol.addView(sub);
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧双圆标：GitHub 作者主页 + 教程（常见问题弹窗）
        LinearLayout badgeCol = new LinearLayout(this);
        badgeCol.setOrientation(LinearLayout.HORIZONTAL);
        ImageView ghImg = new ImageView(this);
        ghImg.setImageResource(getResources().getIdentifier(
                "github", "drawable", getPackageName()));
        View ghBadge = makeBadge(ghImg, "作者主页", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/longtime04")));
            } catch (Exception e) {
                Toast.makeText(this, "打不开浏览器，请手动访问 github.com/longtime04",
                        Toast.LENGTH_LONG).show();
            }
        });
        badgeCol.addView(ghBadge);
        TextView qImg = new TextView(this);
        qImg.setText("?");
        qImg.setTextSize(17);
        qImg.setTextColor(Color.WHITE);
        qImg.getPaint().setFakeBoldText(true);
        qImg.setGravity(Gravity.CENTER);
        View helpBadge = makeBadge(qImg, "教程", v -> showFaqDialog());
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbLp.leftMargin = dp(12);
        badgeCol.addView(helpBadge, hbLp);
        titleRow.addView(badgeCol);
        headerContent.addView(titleRow);

        // 磨砂玻璃卡：插图 + 说明 + 总进度
        LinearLayout glass = new LinearLayout(this);
        glass.setOrientation(LinearLayout.VERTICAL);
        glass.setBackground(roundRect(Color.argb(22, 255, 255, 255), 22,
                Color.argb(56, 255, 255, 255), 1));
        glass.setPadding(dp(16), dp(14), dp(16), dp(13));
        LinearLayout.LayoutParams glassLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glassLp.topMargin = dp(16);
        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        heroRow.addView(new Art(this, Art.HERO), new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView cap = new TextView(this);
        cap.setText("把谷歌服务下载、装进这台手机，十步走完，就能登录谷歌账号、用上商店和地图。");
        cap.setTextSize(12.5f);
        cap.setTextColor(Color.rgb(210, 232, 222));
        cap.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        capLp.leftMargin = dp(13);
        heroRow.addView(cap, capLp);
        glass.addView(heroRow);
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progLp.topMargin = dp(12);
        progTrack = new FrameLayout(this);
        progTrack.setBackground(roundRect(Color.argb(51, 255, 255, 255), 3, 0, 0));
        progFill = new View(this);
        progFill.setBackground(roundRect(Color.rgb(45, 212, 191), 3, 0, 0));
        progTrack.addView(progFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        progRow.addView(progTrack, new LinearLayout.LayoutParams(
                0, dp(5), 1f));
        progText = new TextView(this);
        progText.setText("总进度 0 / 10");
        progText.setTextSize(12);
        progText.setTextColor(Color.rgb(183, 220, 208));
        progText.setPadding(dp(12), 0, 0, 0);
        progRow.addView(progText);
        glass.addView(progRow, progLp);
        headerContent.addView(glass, glassLp);

        // headerWrap 先不入根布局——等滚动层建好后最后叠加到它上面

        // ===== 步骤面板（滚动层，垫在头部之下） =====
        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollInner = new LinearLayout(this);
        scrollInner.setOrientation(LinearLayout.VERTICAL);
        scrollInner.setPadding(dp(14), 0, dp(14), dp(18));
        topSpacer = new View(this);
        scrollInner.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        stepContainer = new LinearLayout(this);
        stepContainer.setOrientation(LinearLayout.VERTICAL);
        // v1.3.3：取消整块白面板（它那条直顶边正是用户点名要去掉的）——
        // 完成卡与每一步各自成卡，卡与卡之间留空
        scrollInner.addView(stepContainer);

        // 打赏卡（v1.3.4）：步骤列表下方，打开聚合收款页
        LinearLayout donateCard = new LinearLayout(this);
        donateCard.setOrientation(LinearLayout.VERTICAL);
        donateCard.setGravity(Gravity.CENTER_HORIZONTAL);
        donateCard.setBackground(roundRect(Color.rgb(255, 253, 244), 18, Color.rgb(222, 198, 132), 1));
        donateCard.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView donateTitle = new TextView(this);
        donateTitle.setText("请作者喝杯咖啡");
        donateTitle.setTextSize(15.5f);
        donateTitle.setTextColor(TITLE_INK);
        donateTitle.getPaint().setFakeBoldText(true);
        donateCard.addView(donateTitle);
        TextView donateSub = new TextView(this);
        donateSub.setText("这个小工具免费开源、为爱发电。觉得好用，可以请作者喝杯咖啡，支持后续更新。");
        donateSub.setTextSize(12);
        donateSub.setTextColor(MUTED);
        donateSub.setGravity(Gravity.CENTER);
        donateSub.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams dnSubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dnSubLp.topMargin = dp(5);
        donateCard.addView(donateSub, dnSubLp);
        Button donateBtn = new Button(this);
        donateBtn.setText("去打赏");
        donateBtn.setTextSize(13.5f);
        donateBtn.setTextColor(Color.WHITE);
        donateBtn.getPaint().setFakeBoldText(true);
        donateBtn.setBackground(tealBtnBg());
        donateBtn.setMinHeight(0);
        donateBtn.setMinimumHeight(0);
        donateBtn.setPadding(dp(24), dp(9), dp(24), dp(9));
        donateBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://longtime04.github.io/donate/")));
            } catch (Exception e) {
                Toast.makeText(this, "打不开浏览器，请手动访问 longtime04.github.io/donate",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams dnBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dnBtnLp.topMargin = dp(11);
        donateCard.addView(donateBtn, dnBtnLp);
        LinearLayout.LayoutParams dncLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dncLp.topMargin = dp(2);
        scrollInner.addView(donateCard, dncLp);
        scroll.addView(scrollInner);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 头部最后加入 = 叠在滚动层之上，步骤滑动时从它底下钻过
        root.addView(headerWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 完成卡（商店装好后出现，独立成卡）
        doneCard = new LinearLayout(this);
        doneCard.setOrientation(LinearLayout.VERTICAL);
        doneCard.setGravity(Gravity.CENTER_HORIZONTAL);
        doneCard.setBackground(roundRect(Color.WHITE, 18, Color.rgb(228, 234, 230), 1));
        doneCard.setPadding(dp(20), dp(18), dp(20), dp(16));
        doneCard.setVisibility(View.GONE);
        doneCard.addView(new Art(this, Art.SEAL), new LinearLayout.LayoutParams(dp(62), dp(62)));
        doneTitle = new TextView(this);
        doneTitle.setText("全部装好了");
        doneTitle.setTextSize(19);
        doneTitle.setTextColor(TITLE_INK);
        doneTitle.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = dp(10);
        doneCard.addView(doneTitle, dtLp);
        doneSub = new TextView(this);
        doneSub.setText("谷歌服务已经装齐，登录谷歌账号就能用了。");
        doneSub.setTextSize(12.5f);
        doneSub.setTextColor(MUTED);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dsLp.topMargin = dp(4);
        doneCard.addView(doneSub, dsLp);
        Button openStore = new Button(this);
        openStore.setText("打开谷歌商店");
        openStore.setTextSize(14);
        openStore.setTextColor(Color.WHITE);
        openStore.getPaint().setFakeBoldText(true);
        openStore.setBackground(tealBtnBg());
        openStore.setMinHeight(0);
        openStore.setMinimumHeight(0);
        openStore.setPadding(dp(26), dp(10), dp(26), dp(10));
        openStore.setOnClickListener(v -> {
            if (!launchApp(PKG_VENDING)) toast("商店已装，手动点桌面图标打开");
        });
        LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        obLp.topMargin = dp(12);
        doneCard.addView(openStore, obLp);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dcLp.bottomMargin = dp(10);
        stepContainer.addView(doneCard, dcLp);

        setContentView(root);

        // 背景画高度跟随头部内容；滚动层顶部留白 = 头部高度 - 30（面板顶边平时藏在圆弧下）
        headerContent.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int hh = headerContent.getHeight();
            if (hh <= 0) return;
            if (headerArt.getHeight() != hh) {
                ViewGroup.LayoutParams alp = headerArt.getLayoutParams();
                alp.height = hh;
                headerArt.setLayoutParams(alp);
            }
            int sp = Math.max(0, hh - dp(30));
            if (topSpacer.getHeight() != sp) {
                ViewGroup.LayoutParams slp = topSpacer.getLayoutParams();
                slp.height = sp;
                topSpacer.setLayoutParams(slp);
            }
        });
    }

    private void addStepCard(int index, Step s) {
        s.index = index;
        // v1.3.3：每一步独立成卡（普通圆角，不描弧——用户亲定）
        LinearLayout rowRoot = new LinearLayout(this);
        rowRoot.setOrientation(LinearLayout.HORIZONTAL);
        rowRoot.setBackground(roundRect(Color.WHITE, 18, Color.rgb(228, 234, 230), 1));
        rowRoot.setPadding(dp(14), dp(13), dp(16), dp(13));
        s.rowRoot = rowRoot;

        // 左侧时间轴：圆章 + 竖线
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        s.medal = new TextView(this);
        s.medal.setText(String.valueOf(index + 1));
        s.medal.setTextSize(13);
        s.medal.getPaint().setFakeBoldText(true);
        s.medal.setGravity(Gravity.CENTER);
        s.medal.setTextColor(Color.rgb(143, 165, 156));
        s.medal.setBackground(ovalBg(Color.WHITE, Color.rgb(201, 214, 208)));
        rail.addView(s.medal, new LinearLayout.LayoutParams(dp(30), dp(30)));
        // v1.3.3：独立卡片后不再画贯通竖线（卡间留空，线连不上）
        rowRoot.addView(rail, new LinearLayout.LayoutParams(
                dp(30), ViewGroup.LayoutParams.MATCH_PARENT));

        // 右侧内容
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mLp.leftMargin = dp(13);

        // 标题整行、状态另起一行（v1.3.2：原并排写法会把长标题挤断行）
        TextView title = new TextView(this);
        title.setText(s.title);
        title.setTextSize(14.5f);
        title.setTextColor(TITLE_INK);
        title.getPaint().setFakeBoldText(true);
        main.addView(title);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(3);
        s.dot = new View(this);
        s.dot.setBackground(ovalBg(Color.rgb(196, 207, 201), 0));
        statusRow.addView(s.dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        s.statusView = new TextView(this);
        s.statusView.setTextSize(11);
        s.statusView.setTextColor(Color.rgb(157, 176, 168));
        s.statusView.setText("检测中…");
        s.statusView.setPadding(dp(4), 0, 0, 0);
        statusRow.addView(s.statusView);
        main.addView(statusRow, srLp);

        TextView desc = new TextView(this);
        desc.setText(s.desc);
        desc.setTextSize(12);
        desc.setTextColor(MUTED);
        desc.setLineSpacing(0, 1.3f);
        desc.setPadding(0, dp(5), 0, 0);
        main.addView(desc);

        s.btn = new Button(this);
        s.btn.setText(s.btnText);
        s.btn.setTextSize(13);
        s.btn.getPaint().setFakeBoldText(true);
        s.btn.setMinHeight(0);
        s.btn.setMinimumHeight(0);
        s.btn.setPadding(dp(20), dp(8), dp(20), dp(8));
        s.btn.setOnClickListener(v -> {
            try { s.action.run(); } catch (Exception e) {
                toast("操作失败：" + e.getMessage());
            }
            refreshStatuses();
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(9);
        main.addView(s.btn, bLp);

        rowRoot.addView(main, mLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        stepContainer.addView(rowRoot, cardLp);
    }

    private void refreshStatuses() {
        int n = steps.size();
        String[] texts = new String[n];
        boolean[] dones = new boolean[n];
        int doneCount = 0, firstOpen = -1;
        for (int i = 0; i < n; i++) {
            Step s = steps.get(i);
            String st;
            try { st = s.status.call(); } catch (Exception e) { st = null; }
            texts[i] = st;
            dones[i] = st != null && st.startsWith("✓");
            if (dones[i]) doneCount++;
            else if (firstOpen < 0) firstOpen = i;
        }
        for (int i = 0; i < n; i++) {
            Step s = steps.get(i);
            if (s.statusView == null) continue;
            boolean done = dones[i];
            boolean active = i == firstOpen;
            // 圆章
            if (done) {
                s.medal.setText("✓");
                s.medal.setTextColor(Color.WHITE);
                s.medal.setBackground(ovalBg(TEAL, TEAL));
            } else if (active) {
                s.medal.setText(String.valueOf(i + 1));
                s.medal.setTextColor(Color.rgb(168, 116, 10));
                s.medal.setBackground(ovalBg(Color.rgb(253, 246, 224), GOLD));
            } else {
                s.medal.setText(String.valueOf(i + 1));
                s.medal.setTextColor(Color.rgb(143, 165, 156));
                s.medal.setBackground(ovalBg(Color.WHITE, Color.rgb(201, 214, 208)));
            }
            // 行底色 + 状态点/文字
            s.rowRoot.setBackground(roundRect(
                    active ? Color.rgb(243, 250, 247) : Color.WHITE, 18,
                    active ? Color.rgb(178, 222, 205) : Color.rgb(228, 234, 230), 1));
            if (done) {
                s.dot.setBackground(ovalBg(Color.rgb(34, 160, 107), 0));
                s.statusView.setText("已完成");
                s.statusView.setTextColor(Color.rgb(27, 138, 87));
            } else if (texts[i] == null) {
                s.dot.setBackground(ovalBg(Color.rgb(196, 207, 201), 0));
                s.statusView.setText("按说明操作");
                s.statusView.setTextColor(Color.rgb(157, 176, 168));
            } else {
                s.dot.setBackground(ovalBg(active ? GOLD : Color.rgb(196, 207, 201), 0));
                s.statusView.setText(texts[i]);
                s.statusView.setTextColor(active ? Color.rgb(168, 116, 10) : Color.rgb(230, 140, 0));
            }
            // 按钮：当前步实心，其余描边
            if (active) {
                s.btn.setBackground(tealBtnBg());
                s.btn.setTextColor(Color.WHITE);
            } else {
                s.btn.setBackground(roundRect(Color.WHITE, 11, Color.rgb(201, 214, 208), 1));
                s.btn.setTextColor(TEAL);
            }
        }
        // 总进度
        if (progText != null) progText.setText("总进度 " + doneCount + " / " + n);
        if (progTrack != null && progFill != null) {
            final int dc = doneCount, total = n;
            progTrack.post(() -> {
                int w = progTrack.getWidth();
                ViewGroup.LayoutParams lp = progFill.getLayoutParams();
                lp.width = total == 0 ? 0 : w * dc / total;
                progFill.setLayoutParams(lp);
            });
        }
        // 完成卡：商店装上即出现；文案按真实完成度说（v1.3.2：7/10 时不再喊「全部装好了」）
        if (doneCard != null) {
            boolean play = isInstalled(PKG_VENDING);
            doneCard.setVisibility(play ? View.VISIBLE : View.GONE);
            if (play && doneTitle != null) {
                if (doneCount >= n) {
                    doneTitle.setText("全部装好了");
                    doneSub.setText("谷歌服务已经装齐，登录谷歌账号就能用了。");
                } else {
                    doneTitle.setText("谷歌商店已装好");
                    doneSub.setText("商店已经能打开了；列表里还有几步没对上，补齐之后更稳。");
                }
            }
        }
    }

    // ================= 教程（常见问题弹窗） =================

    private static final String[][] FAQ = {
        {"找不到「备份」App 怎么办？",
         "在桌面和 设置→应用 里搜「备份」两个字。华为机一般自带；荣耀老机型在「系统和更新 → 备份和恢复」里。如果这台手机确实没有华为备份，这个办法就走不通——本助手主要面向华为 / 荣耀的鸿蒙 4.2 机型。"},
        {"运行备份那步，找不到 2020 年那条备份？",
         "先回第 1 步确认权限全开，并点过「写入数据」、看到「已写入」的提示；再进备份 App，点右上角 ⋮ →「从内部存储恢复」。还是没有，就重启一次手机，再进备份看。"},
        {"恢复时要输的密码是什么？",
         "密码是 eloygomeztv2020。点过「写入数据」后密码会自动复制，直接粘贴就行；手输注意全是小写字母加数字，别多空格。"},
        {"激活设备管理器时没有「我已知晓」？",
         "先勾选界面的风险确认，等倒计时一秒秒走完，按钮会变成「仍要激活」，再点它。中途不要按返回，退出了要重来这一步。"},
        {"改完日期后时间乱了、微信时间不对？",
         "这是第 2 步的正常现象，专门为了让老备份能恢复出来。做到第 6 步把日期改回「自动设置」就全恢复了。中间这段时间先别用手机付款、打车，免得时间对不上。"},
        {"点「安装」提示安装失败或被拦截？",
         "先在第 1 步给本助手开「允许安装应用」。如果是纯净模式拦截，按系统弹窗提示操作，或去 设置 → 系统和更新 → 纯净模式 先关掉，全部装完再打开。"},
        {"Play 服务（microG）闪退、打不开？",
         "先做完第 9 步：权限全给，电池改成「不受限制」；然后重启一次手机再打开。还闪退就卸载它，重走第 8 步装一遍。"},
        {"谷歌商店白屏、转圈、登录不上？",
         "商店需要能正常访问谷歌服务的网络，先确认网络通（能打开 google.com 再试）。第一次打开本来就慢，等一两分钟；还不行去 设置 → 应用 → 谷歌商店 → 存储，清一次缓存再开。"},
        {"我的手机能用这个工具吗？",
         "鸿蒙 4.2 和更早的版本可以；纯血鸿蒙 5.0（HarmonyOS NEXT）装不了；4.3 还没实测，先别试。安卓手机用不上本工具。"},
        {"中途不想装了，怎么恢复原样？",
         "把日期改回「自动设置」（第 6 步），再到 设置 → 应用 里，把谷歌服务助手、Play 框架、Play 服务、谷歌商店逐个卸载即可，不影响手机其他功能。"},
    };

    private void showFaqDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 22, 0, 0));
        box.setPadding(dp(20), dp(18), dp(20), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("常见问题教程");
        t.setTextSize(17);
        t.setTextColor(TITLE_INK);
        t.getPaint().setFakeBoldText(true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16);
        close.setTextColor(MUTED);
        close.setPadding(dp(8), dp(2), dp(4), dp(2));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        box.addView(head);
        TextView hint = new TextView(this);
        hint.setText("点问题展开答案，装不上、找不到、闪退，先在这里查。");
        hint.setTextSize(12);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(4), 0, dp(2));
        box.addView(hint);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < FAQ.length; i++) {
            if (i > 0) {
                View hair = new View(this);
                hair.setBackgroundColor(Color.rgb(238, 242, 239));
                col.addView(hair, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            TextView q = new TextView(this);
            q.setText(FAQ[i][0] + "  ⌄");
            q.setTextSize(14);
            q.setTextColor(TITLE_INK);
            q.getPaint().setFakeBoldText(true);
            q.setPadding(0, dp(12), 0, dp(12));
            col.addView(q);
            TextView a = new TextView(this);
            a.setText(FAQ[i][1]);
            a.setTextSize(12.5f);
            a.setTextColor(MUTED);
            a.setLineSpacing(0, 1.4f);
            a.setPadding(0, 0, 0, dp(12));
            a.setVisibility(View.GONE);
            col.addView(a);
            final TextView ans = a;
            final String qText = FAQ[i][0];
            q.setOnClickListener(v -> {
                boolean show = ans.getVisibility() != View.VISIBLE;
                ans.setVisibility(show ? View.VISIBLE : View.GONE);
                q.setText(qText + (show ? "  ⌃" : "  ⌄"));
            });
        }
        sv.addView(col);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = getResources().getDisplayMetrics().widthPixels - dp(36);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ================= 头部与插图绘制 =================

    /** 头部背景：墨绿渐变 + 右上光晕 + 斜向反光 + 底部纸色圆弧。 */
    private static class HeaderArt extends View {
        HeaderArt(Context c) { super(c); }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float arcTop = h - dp(30), dip = dp(46);
            // v1.3.3：头部只画到圆弧为止，弧下完全透明——滚动内容从弧下透出来，
            // 滑到弧线处被青色吃掉（用户亲定：消失在圆弧，不要被横线切，也不要纸色假底）
            Path shape = new Path();
            shape.moveTo(0, 0);
            shape.lineTo(w, 0);
            shape.lineTo(w, arcTop);
            shape.quadTo(w / 2f, arcTop - dip, 0, arcTop);
            shape.close();
            cv.save();
            cv.clipPath(shape);
            p.setShader(new LinearGradient(0, 0, w * 0.25f, h,
                    new int[]{Color.rgb(12, 42, 34), Color.rgb(15, 61, 47), Color.rgb(23, 96, 74)},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.88f, -h * 0.12f, h * 0.95f,
                    Color.argb(70, 45, 212, 191), Color.argb(0, 45, 212, 191),
                    Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new LinearGradient(0, 0, w * 0.7f, h * 0.5f,
                    Color.argb(32, 255, 255, 255), Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
            cv.restore();
            Path edge = new Path();
            edge.moveTo(0, arcTop);
            edge.quadTo(w / 2f, arcTop - dip, w, arcTop);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.argb(90, 255, 255, 255));
            cv.drawPath(edge, p);
        }
    }

    /** 小插图：HERO=手机+下载箭头+四色点；SEAL=金环对勾。以 74 单位画布绘制后缩放。 */
    private static class Art extends View {
        static final int HERO = 0, SEAL = 1;
        private final int mode;

        Art(Context c, int mode) { super(c); this.mode = mode; }

        @Override protected void onDraw(Canvas cv) {
            int side = Math.min(getWidth(), getHeight());
            if (side == 0) return;
            float s = side / 74f;
            cv.save();
            cv.translate((getWidth() - side) / 2f, (getHeight() - side) / 2f);
            cv.scale(s, s);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (mode == HERO) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                p.setColor(Color.rgb(222, 198, 132));
                cv.drawRoundRect(24, 6, 54, 62, 7, 7, p);
                p.setStrokeWidth(2f);
                cv.drawLine(33, 55, 45, 55, p);
                p.setColor(Color.rgb(45, 212, 191));
                p.setStrokeWidth(3.4f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path a = new Path();
                a.moveTo(39, 20); a.lineTo(39, 36);
                a.moveTo(31.5f, 29.5f); a.lineTo(39, 37); a.lineTo(46.5f, 29.5f);
                cv.drawPath(a, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(66, 133, 244));  cv.drawCircle(12, 16, 3.4f, p);
                p.setColor(Color.rgb(234, 67, 53));   cv.drawCircle(63, 12, 3.4f, p);
                p.setColor(Color.rgb(251, 188, 5));   cv.drawCircle(66, 34, 3.4f, p);
                p.setColor(Color.rgb(52, 168, 83));   cv.drawCircle(10, 40, 3.4f, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Color.rgb(217, 169, 74));
                p.setStrokeWidth(2.6f);
                cv.drawCircle(37, 37, 33, p);
                p.setColor(Color.argb(102, 222, 198, 132));
                p.setStrokeWidth(1f);
                cv.drawCircle(37, 37, 27.5f, p);
                p.setColor(Color.rgb(222, 198, 132));
                p.setStrokeWidth(5.4f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path c = new Path();
                c.moveTo(23.5f, 38.5f); c.lineTo(33, 48); c.lineTo(51.5f, 27.5f);
                cv.drawPath(c, p);
            }
            cv.restore();
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
