package com.sndurkin.locationscout;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.preference.PreferenceManager;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;

// An AsyncTask used to create and initialize the Drive service off the main thread.
public class CreateDriveServiceAsyncTask extends AsyncTask<Void, Void, Object> {

    private WeakReference<Activity> activityRef;
    private WeakReference<DriveEnabledScreen> driveEnabledScreenRef;

    private String accountName;
    private GoogleAccountCredential credential;

    public CreateDriveServiceAsyncTask(Activity activity, DriveEnabledScreen driveEnabledScreen) {
        activityRef = new WeakReference<>(activity);
        driveEnabledScreenRef = new WeakReference<>(driveEnabledScreen);

        accountName = PreferenceManager.getDefaultSharedPreferences(activity).getString(Strings.PREF_ACCOUNT, null);
        if(accountName != null) {
            credential = GoogleAccountCredential.usingOAuth2(activity, Arrays.asList(DriveScopes.DRIVE));
            credential.setSelectedAccountName(accountName);
        }
    }

    public boolean hasInvalidAccount() {
        return accountName != null && credential.getSelectedAccountName() == null;
    }

    @Override
    protected Object doInBackground(Void... params) {
        if(accountName == null || hasInvalidAccount()) {
            return null;
        }

        try {
            credential.setBackOff(new ExponentialBackOff());
            credential.getToken();      // This will throw a GoogleAuthException if we are not authorized.

            HttpRequestInitializer initializer = new HttpRequestInitializer() {
                @Override
                public void initialize(HttpRequest httpRequest) throws IOException {
                    credential.initialize(httpRequest);
                    httpRequest.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff()));
                    httpRequest.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
                }
            };

            return new Drive.Builder(AndroidHttp.newCompatibleTransport(), new GsonFactory(), initializer).build();
        }
        catch(UserRecoverableAuthException e) {
            return e.getIntent();
        }
        catch(UserRecoverableAuthIOException e) {
            return e.getIntent();
        }
        catch(GoogleAuthException e) {
            // Ignore for now.
        }
        catch(IOException e) {
            // Ignore for now.
        }

        return null;
    }

    @Override
    protected void onPostExecute(Object obj) {
        if(obj == null) {
            return;
        }

        if(obj instanceof Intent) {
            Activity activity = activityRef.get();
            if(activity != null) {
                activity.startActivityForResult((Intent) obj, RequestCodes.REQUEST_AUTHORIZATION);
            }
        }
        else if(obj instanceof Drive) {
            DriveEnabledScreen driveEnabledScreen = driveEnabledScreenRef.get();
            if(driveEnabledScreen != null) {
                driveEnabledScreen.setDriveService((Drive) obj);
            }
        }
    }
}
