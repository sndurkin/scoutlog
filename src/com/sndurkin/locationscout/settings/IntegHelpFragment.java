package com.sndurkin.locationscout.settings;


import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.Application;
import com.sndurkin.locationscout.R;
import com.sndurkin.locationscout.storage.DriveSyncAdapter;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

public class IntegHelpFragment extends Fragment {

    protected View view;

    protected TextView helpText2;

    protected SharedPreferences preferences;
    protected Tracker tracker;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setView(inflater, R.layout.integ_help_fragment, container);

        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        tracker = Application.getInstance().getTracker();

        tracker.setScreenName(Strings.INTEG_HELP_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        final String linkStr = "https://www.google.com/bookmarks";
        String importBookmarksStr = getString(R.string.integ_help_text_2, linkStr);
        helpText2 = (TextView) view.findViewById(R.id.integ_help_text_2);
        helpText2.setText(importBookmarksStr);

        final Button integEmailButton = (Button) view.findViewById(R.id.integ_email_button);
        integEmailButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{ "scoutlogapp@gmail.com" });
                intent.putExtra(Intent.EXTRA_SUBJECT, "ScoutLog import");

                try {
                    startActivity(Intent.createChooser(intent, getString(R.string.send_email)));
                }
                catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(getActivity(), R.string.no_email_apps, Toast.LENGTH_SHORT).show();
                }
            }
        });

        return view;
    }

    protected void setView(LayoutInflater inflater, int layoutId, ViewGroup container) {
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }

        try {
            view = inflater.inflate(layoutId, container, false);
        }
        catch (InflateException e) {
            // Return view as it is.
            CrashlyticsCore.getInstance().logException(e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if(isAdded()) {
            getActivity().setTitle(R.string.integ_help_title);
        }
    }

}
