package com.sndurkin.locationscout.storage;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class DriveSyncService extends Service {

    private static final Object syncAdapterLock = new Object();
    private static DriveSyncAdapter driveSyncAdapter = null;

    @Override
    public void onCreate() {
        synchronized(syncAdapterLock) {
            if(driveSyncAdapter == null) {
                driveSyncAdapter = new DriveSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return driveSyncAdapter.getSyncAdapterBinder();
    }
}
