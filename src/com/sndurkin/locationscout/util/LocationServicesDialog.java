package com.sndurkin.locationscout.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.LocationManager;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;

import com.sndurkin.locationscout.R;

public class LocationServicesDialog extends AlertDialog {

    // Shows the dialog if necessary, and returns true if it was shown.
    public static boolean showIfNecessary(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
           !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            new LocationServicesDialog(context).show();
            return true;
        }

        return false;
    }

    private LocationServicesDialog(final Context context) {
        super(context);

        setTitle(R.string.location_services_disabled_title);
        setMessage(context.getString(R.string.location_services_disabled_message));

        OnClickListener buttonListener = new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch(which) {
                    case BUTTON_NEGATIVE:
                        // Do nothing, allow the dialog to dismiss.
                        break;
                    case BUTTON_POSITIVE:
                        // Display the location settings.
                        try {
                            context.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        }
                        catch(ActivityNotFoundException e) {
                            context.startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                        break;
                }
            }
        };

        setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.ignore), buttonListener);
        setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.settings), buttonListener);
    }

}
