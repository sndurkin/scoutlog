package com.sndurkin.locationscout.util;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.TypedValue;
import android.widget.ImageView;

import com.bumptech.glide.DrawableRequestBuilder;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.sndurkin.locationscout.PhotoInfo;
import com.sndurkin.locationscout.R;

import java.io.File;

public class PhotoLoader {

    private static TypedValue missingPhoto = null;
    private static TypedValue noPhotoSet = null;
    /*
    private static RequestListener<String, GlideDrawable> listener = new RequestListener<String, GlideDrawable>() {
        @Override
        public boolean onException(Exception e, String s, Target<GlideDrawable> target, boolean b) {
            CrashlyticsCore.getInstance().logException(e);
            return false;
        }

        @Override
        public boolean onResourceReady(GlideDrawable resource, String model, Target<GlideDrawable> target, boolean isFromMemoryCache, boolean isFirstResource) {
            return false;
        }
    };
    */

    public static void loadIntoImageView(Context context, PhotoInfo photo, ImageView imageView) {
        loadIntoImageView(context, photo, imageView, ImageView.ScaleType.CENTER_CROP);
    }

    public static void loadIntoImageView(Context context, PhotoInfo photo, ImageView imageView, ImageView.ScaleType scaleType) {
        initPhotoResources(context);

        RequestManager requestManager = Glide.with(context);
        DrawableRequestBuilder requestBuilder = null;
        if(photo != null) {
            // First try to loadIntoImageView the local file, then try to loadIntoImageView from Drive.
            if(photo.path != null) {
                File photoFile = new File(photo.path);
                if(photoFile.exists()) {
                    // Load image locally.
                    requestBuilder = requestManager.load(Uri.fromFile(photoFile));
                }
            }

            if(requestBuilder == null && photo.getRemoteId() != null) {
                // Load image remotely.
                requestBuilder = requestManager.from(String.class)
                        .load(photo.getRemoteId());

                // The exception logging from the listener was causing too many
                // FileNotFoundExceptions in Crashlytics.
                //      .listener(listener);
            }
        }

        if(requestBuilder == null) {
            // No image is set.
            imageView.setImageDrawable(context.getResources().getDrawable(noPhotoSet.resourceId));
            return;
        }

        if(scaleType == ImageView.ScaleType.FIT_CENTER) {
            requestBuilder = requestBuilder.fitCenter();
        }
        else {
            requestBuilder = requestBuilder.centerCrop();
        }

        requestBuilder
                .placeholder(R.color.gallery_placeholder)
                .error(missingPhoto.resourceId)
                .into(imageView);
    }

    private static void initPhotoResources(Context context) {
        if(missingPhoto == null) {
            missingPhoto = new TypedValue();
            context.getApplicationContext().getTheme().resolveAttribute(R.attr.imageMissingPhoto, missingPhoto, true);
        }
        if(noPhotoSet == null) {
            noPhotoSet = new TypedValue();
            context.getApplicationContext().getTheme().resolveAttribute(R.attr.imageNoPhotoSet, noPhotoSet, true);
        }
    }

    // This function is used to reset the references to photo resources so that if the
    // theme has been changed, the resources will be refreshed.
    public static void resetPhotoResources(Context context) {
        missingPhoto = noPhotoSet = null;
        initPhotoResources(context);
    }

}
