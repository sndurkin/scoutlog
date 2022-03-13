package com.sndurkin.locationscout.integ;


import android.content.Context;
import android.content.res.Resources;

import com.sndurkin.locationscout.R;

public class ParseResult {

    public boolean success = false;

    public int locationsImported;
    public int locationsIgnored;
    public int tagsImported;

    public String toString(Context context) {
        Resources res = context.getResources();
        StringBuffer sb = new StringBuffer();
        sb.append(res.getQuantityString(R.plurals.import_complete_notification_message_imported, locationsImported, locationsImported));
        sb.append("; ");
        sb.append(res.getQuantityString(R.plurals.import_complete_notification_message_ignored, locationsIgnored, locationsIgnored));
        return sb.toString();
    }

}
