package com.sndurkin.locationscout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;

import com.sndurkin.locationscout.integ.ExportService;
import com.sndurkin.locationscout.util.MiscUtils;

import java.util.*;

// This class enhances the functionality of LocalBroadcastManager by storing the last broadcast
// for each action so that it can still be retrieved by activities/fragments after they resume.
// Receivers have to be registered under a unique name so that they can notify GlobalBroadcastManager
// that they're finished with a broadcast.
public class GlobalBroadcastManager {

    private Context context;
    private LocalBroadcastManager broadcastManager;
    private GlobalBroadcastReceiver broadcastReceiver;
    private Set<String> registeredGlobalReceivers;
    private Map<String, Intent> savedBroadcasts;
    private Map<String, Set<String>> removedBroadcasts;

    // Singleton pattern
    private static GlobalBroadcastManager instance;
    public static GlobalBroadcastManager getInstance(Context context) {
        if(instance == null) {
            instance = new GlobalBroadcastManager(context.getApplicationContext());
        }
        return instance;
    }
    private GlobalBroadcastManager(Context context) {
        this.context = context;
        broadcastManager = LocalBroadcastManager.getInstance(context);
        broadcastReceiver = new GlobalBroadcastReceiver();
        registeredGlobalReceivers = new HashSet<>();
        savedBroadcasts = new HashMap<>();
        removedBroadcasts = new HashMap<>();
    }

    public boolean sendBroadcast(Intent intent) {
        intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
        registerGlobalReceiver(intent.getAction());
        return broadcastManager.sendBroadcast(intent);
    }

    // This function is only safe to use if called from the main thread.
    public void sendBroadcastSync(Intent intent) {
        intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
        registerGlobalReceiver(intent.getAction());
        broadcastManager.sendBroadcastSync(intent);
    }

    public void registerReceiver(String receiverName, BroadcastReceiver receiver, IntentFilter filter, boolean sendLastBroadcast) {
        if(sendLastBroadcast) {
            Iterator<String> iter = filter.actionsIterator();
            while(iter.hasNext()) {
                String action = iter.next();
                registerGlobalReceiver(action);
            }
        }

        broadcastManager.registerReceiver(receiver, filter);

        // This logic is split up from the above logic because it's possible to have a race condition
        // where the last broadcast is sent and processed, and a new broadcast is sent before the receiver
        // is registered. So, we first register the global receiver (if necessary), then we register
        // the receiver, then we send out the last broadcast.
        if(sendLastBroadcast) {
            Iterator<String> iter = filter.actionsIterator();
            while(iter.hasNext()) {
                String action = iter.next();
                sendLastBroadcast(receiverName, receiver, action);
            }
        }
    }

    private void registerGlobalReceiver(String action) {
        if(!registeredGlobalReceivers.contains(action)) {
            if(ExportService.EXPORT_BROADCAST.equals(action)) {
                MiscUtils.logd("global receiver registered");
            }

            broadcastManager.registerReceiver(broadcastReceiver, new IntentFilter(action));
            registeredGlobalReceivers.add(action);
        }
    }

    public void unregisterReceiver(BroadcastReceiver receiver) {
        if(receiver == null) {
            return;
        }
        broadcastManager.unregisterReceiver(receiver);
    }

    public void sendLastBroadcast(String receiverName, BroadcastReceiver receiver, String action) {
        Intent lastBroadcast = getLastBroadcast(receiverName, action);
        if(lastBroadcast != null) {
            if(ExportService.EXPORT_BROADCAST.equals(action)) {
                MiscUtils.logd("Sending last broadcast");
            }
            receiver.onReceive(context, lastBroadcast);
        }
    }
    public Intent getLastBroadcast(String action) {
        return savedBroadcasts.get(action);
    }
    public Intent getLastBroadcast(String receiverName, String action) {
        if(removedBroadcasts.containsKey(action) && removedBroadcasts.get(action).contains(receiverName)) {
            return null;
        }

        return getLastBroadcast(action);
    }

    // Removes a saved broadcast on a per-receiver basis.
    public void removeLastBroadcast(String receiverName, String action) {
        if(!removedBroadcasts.containsKey(action)) {
            Set<String> receiversSet = new HashSet<String>();
            receiversSet.add(receiverName);
            removedBroadcasts.put(action, receiversSet);
        }
        else {
            removedBroadcasts.get(action).add(receiverName);
        }
    }

    // Removes a saved broadcast for all receivers.
    public void removeLastBroadcast(String action) {
        removedBroadcasts.remove(action);
        savedBroadcasts.remove(action);

        if(ExportService.EXPORT_BROADCAST.equals(action)) {
            MiscUtils.logd("export broadcast removed");
        }
    }

    class GlobalBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(ExportService.EXPORT_BROADCAST.equals(intent.getAction())) {
                MiscUtils.logd("export broadcast saved");
            }
            removedBroadcasts.remove(intent.getAction());
            savedBroadcasts.put(intent.getAction(), intent);
        }
    }

}
