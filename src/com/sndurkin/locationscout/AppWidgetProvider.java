package com.sndurkin.locationscout;


import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.sndurkin.locationscout.util.Strings;

public class AppWidgetProvider extends android.appwidget.AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        for(int i = 0; i < appWidgetIds.length; ++i) {
            int appWidgetId = appWidgetIds[i];

            try {
                Intent intent = new Intent("android.intent.action.MAIN");
                intent.addCategory("android.intent.category.LAUNCHER");

                //intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra(Strings.PARAM_CREATED_LOCATION_FROM_WIDGET, true);
                intent.setComponent(new ComponentName("com.sndurkin.locationscout", MainActivity.class.getCanonicalName()));
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.appwidget);
                views.setOnClickPendingIntent(R.id.appwidget, pendingIntent);
                appWidgetManager.updateAppWidget(appWidgetId, views);
            }
            catch (ActivityNotFoundException e) {
                Toast.makeText(context.getApplicationContext(), "There was a problem loading the application", Toast.LENGTH_SHORT).show();
            }
        }
    }



}
