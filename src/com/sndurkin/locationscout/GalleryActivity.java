package com.sndurkin.locationscout;

import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.Tracker;
import com.google.api.services.drive.Drive;
import com.sndurkin.locationscout.util.PhotoLoader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// This is used to view the photos for a location, one at a time,
// like a gallery app.
public class GalleryActivity extends AppCompatActivity implements DriveEnabledScreen {

    protected Tracker tracker;
    protected Drive driveService;

    protected ViewPager viewPager;
    protected ViewPagerAdapter pagerAdapter;
    protected ViewPager.OnPageChangeListener pageChangeListener;

    protected PhotoInfo[] photoInfos;
    protected ImageView[] imageViews;
    protected boolean[] photosLoaded;
    protected int startIdx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Black);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.gallery_activity);
        setupActionBar();
        setTitle("");

        new CreateDriveServiceAsyncTask(this, this).execute();

        tracker = Application.getInstance().getTracker();

        setupViewPager();

        if(getIntent().getExtras() != null) {
            List<PhotoInfo> photoInfoList = getIntent().getExtras().getParcelableArrayList("photoInfoList");
            if(photoInfoList != null) {
                photoInfos = photoInfoList.toArray(new PhotoInfo[0]);
                imageViews = new ImageView[photoInfos.length];
                photosLoaded = new boolean[photoInfos.length];

                startIdx = getIntent().getExtras().getInt("photoInfoIdx");

                for(int i = 0; i < photoInfos.length; ++i) {
                    ViewGroup viewGroup = (ViewGroup) getLayoutInflater().inflate(R.layout.gallery_image, viewPager, false);
                    pagerAdapter.addView(viewGroup);
                    imageViews[i] = (ImageView) viewGroup.findViewById(R.id.gallery_image);
                    photosLoaded[i] = false;
                }
                viewPager.setAdapter(pagerAdapter);

                // After setContentView() is invoked, the event queue will contain a message asking for a relayout,
                // so anything you post to the queue will happen after the layout pass. - Romain Guy
                //
                // http://stackoverflow.com/a/24035591
                viewPager.post(new Runnable() {
                    @Override
                    public void run() {
                        pageChangeListener.onPageSelected(startIdx);
                        viewPager.setCurrentItem(startIdx);
                    }
                });
            }
        }
    }

    protected void setupViewPager() {
        viewPager = (ViewPager) findViewById(R.id.gallery_viewpager);
        pagerAdapter = new ViewPagerAdapter();

        pageChangeListener = new ViewPager.SimpleOnPageChangeListener() {

            @Override
            public void onPageSelected(int position) {
                if (photoInfos == null) {
                    return;
                }

                if (position >= photoInfos.length) {
                    CrashlyticsCore.getInstance().logException(new RuntimeException("Something went wrong. position: " + position + ", photoInfos.len: " + photoInfos.length));
                    return;
                }

                if(!photosLoaded[position]) {
                    PhotoLoader.loadIntoImageView(GalleryActivity.this, photoInfos[position], imageViews[position], ImageView.ScaleType.FIT_CENTER);
                    photosLoaded[position] = true;
                }
            }

        };
        viewPager.addOnPageChangeListener(pageChangeListener);

        viewPager.setPageTransformer(true, new ViewPager.PageTransformer() {
            @Override
            public void transformPage(View page, float position) {
                float alpha, scale, translationX;
                if (position > 0 && position < 1) {
                    alpha = (1 - position);
                    scale = 0.75f + (1 - 0.75f) * (1 - Math.abs(position));
                    translationX = (page.getWidth() * -position);
                } else {
                    alpha = 1;
                    scale = 1;
                    translationX = 0;
                }

                page.setAlpha(alpha);
                page.setTranslationX(translationX);
                page.setScaleX(scale);
                page.setScaleY(scale);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public Drive getDriveService() {
        return driveService;
    }

    @Override
    public void setDriveService(Drive driveService) {
        this.driveService = driveService;
        Glide.get(this).register(String.class, InputStream.class, new DriveModelLoader.Factory(driveService, true));
        pageChangeListener.onPageSelected(viewPager.getCurrentItem());
    }

    protected void setupActionBar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        int toolbarTopPadding = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            toolbarTopPadding = getResources().getDimensionPixelSize(resourceId);
        }
        toolbar.setPadding(0, toolbarTopPadding, 0, 0);
    }

}
