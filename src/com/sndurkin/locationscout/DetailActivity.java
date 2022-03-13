package com.sndurkin.locationscout;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.model.LatLng;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.LocationModelChanges;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.util.Date;

import static android.support.v7.app.ActionBar.Tab;

public class DetailActivity extends AppCompatActivity implements ActionBar.TabListener {

    private Tracker tracker;

    private Long locationId;
    private boolean justCreatedLocation;
    private boolean alreadyDisplayedSnackbar = false;

    private Tab detailTab;
    private Tab photosTab;

    private ViewPager viewPager;
    private DetailViewPagerAdapter viewPagerAdapter;

    private Snackbar snackbar;

    private DetailFragment detailFragment;
    private DetailPhotosFragment photosFragment;

    public static final int EXIT_STATE_NORMAL = 0;
    public static final int EXIT_STATE_DELETED = 1;
    public static final int EXIT_STATE_UNDO_CREATED = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = Application.getInstance().getTracker();
        tracker.setScreenName(Strings.DETAIL_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        setupActionBar();
        setContentView(R.layout.detail_activity);

        viewPager = (ViewPager) findViewById(R.id.detail_pager);
        viewPagerAdapter = new DetailViewPagerAdapter(getSupportFragmentManager());
        viewPager.setAdapter(viewPagerAdapter);
        viewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                getSupportActionBar().setSelectedNavigationItem(position);
            }
        });

        snackbar = (Snackbar) findViewById(R.id.snackbar);

        if(savedInstanceState != null) {
            getSupportActionBar().setSelectedNavigationItem(savedInstanceState.getInt(Strings.PARAM_CURRENT_TAB));
            alreadyDisplayedSnackbar = savedInstanceState.getBoolean("alreadyDisplayedSnackbar");
        }

        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            if(extras.containsKey(Strings.LOCATION_ID)) {
                locationId = extras.getLong(Strings.LOCATION_ID);
            }
            justCreatedLocation = extras.getBoolean(Strings.PARAM_JUST_CREATED_LOCATION);
        }

        if(justCreatedLocation && !alreadyDisplayedSnackbar) {
            Snackbar.ShowConfig config = snackbar.new ShowConfig();
            config.text = getString(R.string.place_created);
            config.expireTime = Snackbar.DEFAULT_TOAST_EXPIRE_TIME;
            config.listener = snackbar.new Listener() {
                @Override
                public void onExpired() { }

                @Override
                public void onButtonClicked() {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_BUTTON)
                            .setLabel(Strings.LBL_ADD_PLACE_UNDO)
                            .build());

                    undoCreatedLocation();
                }
            };
            snackbar.show(config);

            alreadyDisplayedSnackbar = true;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.detail_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                exit();
                return true;
            case R.id.detail_action_photo_add_camera:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_ADD_PHOTO_VIA_CAMERA)
                        .build());
                getSupportActionBar().selectTab(photosTab);
                photosFragment.openCameraForPhoto();
                return true;
            case R.id.detail_action_photo_add_gallery:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_ADD_PHOTO_VIA_GALLERY)
                        .build());
                getSupportActionBar().selectTab(photosTab);
                photosFragment.addPhotoFromGallery();
                return true;
            case R.id.detail_action_delete:
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_MENU_ITEM)
                        .setLabel(Strings.LBL_DELETE_PLACE)
                        .build());
                deleteLocation();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        exit();
    }

    public void exit() {
        Intent data = new Intent();

        LocationModelChanges locationChanges = new LocationModelChanges();
        locationChanges.mergeWith(detailFragment.getLocationChanges());
        locationChanges.mergeWith(photosFragment.getLocationChanges());

        data.putExtra(Strings.PARAM_LOCATION_CHANGES, locationChanges);
        data.putExtra(Strings.PARAM_TAG_NAME_CHANGED, detailFragment.haveTagNamesChanged());
        data.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, detailFragment.haveTagColorsChanged());
        data.putExtra(Strings.PARAM_JUST_CREATED_LOCATION, justCreatedLocation);
        data.putExtra(Strings.PARAM_EXIT_STATE, EXIT_STATE_NORMAL);

        setResult(RESULT_OK, data);
        finish();
    }

    public LocationInfo getLocationInfo() {
        return detailFragment.getLocationInfo();
    }
    public void setLocation(LatLng latLng) {
        detailFragment.setLocation(latLng);
    }
    public Date getDate() { return detailFragment.getDate(); }
    public void setDate(Date date) { detailFragment.setDate(date.getTime()); }

    protected void deleteLocation() {
        DatabaseHelper.getInstance(this).tentativelyDeleteLocation(locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Intent data = new Intent();
                data.putExtra(Strings.PARAM_EXIT_STATE, EXIT_STATE_DELETED);
                data.putExtra(Strings.PARAM_TAG_NAME_CHANGED, detailFragment.haveTagNamesChanged());
                data.putExtra(Strings.PARAM_TAG_COLOR_CHANGED, detailFragment.haveTagColorsChanged());
                data.putExtra(Strings.LOCATION_ID, locationId);
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    protected void undoCreatedLocation() {
        DatabaseHelper.getInstance(this).deleteLocation(locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                Intent data = new Intent();
                data.putExtra(Strings.PARAM_EXIT_STATE, EXIT_STATE_UNDO_CREATED);
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(Strings.PARAM_CURRENT_TAB, getSupportActionBar().getSelectedNavigationIndex());
        state.putBoolean(Strings.PARAM_JUST_CREATED_LOCATION, justCreatedLocation);
        state.putBoolean("alreadyDisplayedSnackbar", alreadyDisplayedSnackbar);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        getSupportActionBar().setSelectedNavigationItem(savedInstanceState.getInt(Strings.PARAM_CURRENT_TAB));
        justCreatedLocation = savedInstanceState.getBoolean(Strings.PARAM_JUST_CREATED_LOCATION);
    }

    @Override
    protected void onPause() {
        snackbar.hide(false);
        super.onPause();
    }

    @Override
    public void setTitle(CharSequence title) {
        if(title == null || title.length() == 0) {
            title = getString(R.string.untitled);
        }
        super.setTitle(title);
    }

    protected void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();

        // Show back button.
        actionBar.setDisplayHomeAsUpEnabled(true);

        // Specify that tabs should be displayed in the action bar.
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // Add tabs for general details and photos.
        detailTab = actionBar.newTab();
        detailTab.setText(R.string.details_tab_title);
        detailTab.setTabListener(this);
        actionBar.addTab(detailTab);

        photosTab = actionBar.newTab();
        photosTab.setText(R.string.photos_tab_title);
        photosTab.setTabListener(this);
        actionBar.addTab(photosTab);
    }

    protected Fragment getFragmentFromIdx(int idx) {
        switch(idx) {
            case 0:
                if(detailFragment == null) {
                    detailFragment = new DetailFragment();
                }
                return detailFragment;
            case 1:
                if(photosFragment == null) {
                    photosFragment = new DetailPhotosFragment();
                }
                return photosFragment;
        }

        return null;
    }

    @Override
    public void onTabSelected(Tab tab, FragmentTransaction unused) {
        if(viewPager != null) {
            viewPager.setCurrentItem(tab.getPosition());
        }
    }

    @Override
    public void onTabUnselected(Tab tab, FragmentTransaction unused) {
        UIUtils.hideKeyboard(this);
    }

    @Override
    public void onTabReselected(Tab tab, FragmentTransaction unused) { }

    class DetailViewPagerAdapter extends FragmentPagerAdapter {

        public DetailViewPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            Fragment fragment = (Fragment) super.instantiateItem(container, position);
            switch(position) {
                case 0:
                    detailFragment = (DetailFragment) fragment;
                    break;
                case 1:
                    photosFragment = (DetailPhotosFragment) fragment;
                    break;
            }

            return fragment;
        }

        @Override
        public Fragment getItem(int i) {
            return getFragmentFromIdx(i);
        }

        @Override
        public int getCount() {
            return 2;
        }

    }

}
