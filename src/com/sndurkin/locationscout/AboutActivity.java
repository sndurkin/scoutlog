package com.sndurkin.locationscout;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

public class AboutActivity extends AppCompatActivity {

    private Tracker tracker;

    private TextView aboutText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));
        
        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.ABOUT_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        setTitle(R.string.about_title);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setContentView(R.layout.about_activity);

        aboutText = (TextView) findViewById(R.id.about_text);
        aboutText.setMovementMethod(new LinkMovementMethod());

        // Add the app name & version.
        String appName = getString(R.string.app_name);
        String appVersion = null;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = getString(R.string.about_version, packageInfo.versionName);
        }
        catch(PackageManager.NameNotFoundException e) {
            // Do nothing.
        }

        SpannableStringBuilder aboutStr = new SpannableStringBuilder();
        aboutStr.append(appName);
        if(appVersion != null) {
            aboutStr.append(" ").append(appVersion);
            if(BuildConfig.DEBUG) {
                aboutStr.append(" (dev)");
            }
        }
        aboutStr.append("\n© 2014 - 2016 Sean Durkin");

        // Add in the credit string.
        final String nameStr = "Shahriar Emil";
        String creditStr = getString(R.string.about_credit, nameStr);
        SpannableString creditLink = new SpannableString(creditStr);
        int nameIdx = creditStr.indexOf(nameStr);
        creditLink.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(nameStr)
                        .build());

                //startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.behance.net/shahriaremil")));
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.shahriaremil.com/")));
            }
        }, nameIdx, nameIdx + nameStr.length(), 0);
        aboutStr.append("\n\n").append(creditLink);

        // Add in an "email me" link.
        final String emailStr = getString(R.string.about_email);
        SpannableString emailLink = new SpannableString(emailStr);
        emailLink.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(emailStr)
                        .build());

                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"scoutlogapp@gmail.com"});
                intent.putExtra(Intent.EXTRA_SUBJECT, "ScoutLog app");

                try {
                    startActivity(Intent.createChooser(intent, getString(R.string.send_email)));
                }
                catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(AboutActivity.this, R.string.no_email_apps, Toast.LENGTH_SHORT).show();
                }
            }
        }, 0, emailLink.length(), 0);
        aboutStr.append("\n\n").append(getString(R.string.about_concerns)).append(" ").append(emailLink);

        // Add in the licenses link.
        final String licensesStr = getString(R.string.about_licenses);
        SpannableString licensesLink = new SpannableString(licensesStr);
        licensesLink.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(licensesStr)
                        .build());

                WebView webView = new WebView(AboutActivity.this);
                webView.loadUrl("file:///android_res/raw/licenses.html");
                new AlertDialog.Builder(AboutActivity.this)
                        .setTitle(R.string.about_licenses)
                        .setView(webView)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }, 0, licensesLink.length(), 0);
        aboutStr.append("\n\n").append(licensesLink);

        aboutText.setText(aboutStr);

        /*
        The following code is used to write the Google Play Services attribution text
        to a text file so it doesn't need to be called within the app.

        String s = GooglePlayServicesUtil.getOpenSourceSoftwareLicenseInfo(this);
        File sdcard = Environment.getExternalStorageDirectory();
        File f = new File(sdcard, "googleplayservices.txt");
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(f));
            bos.write(s.getBytes(Charset.forName("UTF-8")));
            bos.close();
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
        */
    }

}
