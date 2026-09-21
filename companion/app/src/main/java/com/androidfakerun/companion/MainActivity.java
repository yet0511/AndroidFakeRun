package com.androidfakerun.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Android Fake Run");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView message = new TextView(this);
        message.setText("这是 Windows 控制端的手机配套组件。\n\n请在开发者选项的“选择模拟位置信息应用”中选择 Android Fake Run，然后回到电脑点击连接。运行期间请勿强制停止本应用。\n\n红米/小米还建议开启“USB 调试（安全设置）”。");
        message.setTextSize(17);
        message.setLineSpacing(0, 1.25f);
        message.setPadding(0, pad, 0, pad);
        layout.addView(message, new LinearLayout.LayoutParams(-1, -2));

        Button settings = new Button(this);
        settings.setText("打开开发者选项");
        settings.setOnClickListener((View v) -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        layout.addView(settings, new LinearLayout.LayoutParams(-1, -2));

        TextView status = new TextView(this);
        status.setText("连接状态由 Windows 程序显示。本页面可以留在后台。");
        status.setTextSize(14);
        status.setPadding(0, pad, 0, 0);
        layout.addView(status, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
    }
}
