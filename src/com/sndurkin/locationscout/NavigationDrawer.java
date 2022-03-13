package com.sndurkin.locationscout;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.settings.SettingsActivity;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.Versions;

public class NavigationDrawer extends RelativeLayout {

    private DrawerLayout navDrawerLayout;
    private ActionBarDrawerToggle navDrawerToggle;

    private NavigationDrawerListener listener;

    private SharedPreferences preferences;
    private Tracker tracker;

    private ImageView[] locationsDisplayIcons;
    private int[] locationsDisplayAttrs;
    private int[] locationsDisplaySelectedAttrs;

    private Integer selectedIntrinsicItemIdx = null;
    private LinearLayout[] intrinsicItems;
    private ImageView[] intrinsicItemIcons;
    private int[] intrinsicItemAttrs;
    private int[] intrinsicItemSelectedAttrs;
    private TextView[] intrinsicItemTexts;

    private int defaultTextColor;

    private LinearLayout navDrawerSettingsItem;

    public NavigationDrawer(Context context) {
        this(context, null);
    }

    public NavigationDrawer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NavigationDrawer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        Versions.checkAndUpdate(preferences, Strings.NAV_DRAWER_VERSION, Versions.Defaults.NAV_DRAWER_VERSION, null);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        setupLocationsDisplayItems();
        setupIntrinsicItems();
        updateLocationsDisplayIcons();

        // Settings
        navDrawerSettingsItem = (LinearLayout) findViewById(R.id.settings_item);
        navDrawerSettingsItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_NAV_DRAWER_ITEM)
                        .setLabel(Strings.LBL_SETTINGS)
                        .build());

                navDrawerLayout.closeDrawer(GravityCompat.START);
                onCloseDrawer();
                getContext().startActivity(new Intent(getContext(), SettingsActivity.class));
            }
        });
    }

    protected void setupLocationsDisplayItems() {
        locationsDisplayIcons = new ImageView[] {
                (ImageView) findViewById(R.id.grid_icon),
                (ImageView) findViewById(R.id.list_icon),
                (ImageView) findViewById(R.id.map_icon)
        };

        locationsDisplayAttrs = new int[] {
                R.attr.navDrawerGridDrawable,
                R.attr.navDrawerListDrawable,
                R.attr.navDrawerMapDrawable
        };

        locationsDisplaySelectedAttrs = new int[]  {
                R.attr.navDrawerSelectedGridDrawable,
                R.attr.navDrawerSelectedListDrawable,
                R.attr.navDrawerSelectedMapDrawable
        };

        for(int displayIdx = 0; displayIdx < locationsDisplayIcons.length; ++displayIdx) {
            final int newDisplayIdx = displayIdx;
            locationsDisplayIcons[displayIdx].setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(switchDisplay(newDisplayIdx)) {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_NAV_DRAWER_ITEM)
                                .setLabel(Strings.LBL_NAV_DISPLAY_ITEMS[newDisplayIdx])
                                .build());
                    }
                }
            });
        }
    }

    protected void setupIntrinsicItems() {
        intrinsicItems = new LinearLayout[] {
                (LinearLayout) findViewById(R.id.locations_item),
                (LinearLayout) findViewById(R.id.tags_item),
                (LinearLayout) findViewById(R.id.search_item)
        };

        intrinsicItemIcons = new ImageView[] {
                (ImageView) findViewById(R.id.locations_icon),
                (ImageView) findViewById(R.id.tags_icon),
                (ImageView) findViewById(R.id.search_icon)
        };

        intrinsicItemAttrs = new int[] {
                R.attr.navDrawerLocationsDrawable,
                R.attr.navDrawerTagsDrawable,
                R.attr.navDrawerSearchDrawable
        };

        intrinsicItemSelectedAttrs = new int[] {
                R.attr.navDrawerSelectedLocationsDrawable,
                R.attr.navDrawerSelectedTagsDrawable,
                R.attr.navDrawerSelectedSearchDrawable
        };

        intrinsicItemTexts = new TextView[] {
                (TextView) findViewById(R.id.locations_text),
                (TextView) findViewById(R.id.tags_text),
                (TextView) findViewById(R.id.search_text)
        };

        defaultTextColor = intrinsicItemTexts[0].getCurrentTextColor();

        for(int i = 0; i < intrinsicItems.length; ++i) {
            final int idx = i;
            intrinsicItems[i].setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_NAV_DRAWER_ITEM)
                            .setLabel(Strings.LBL_NAV_INTRINSIC_ITEMS[idx])
                            .build());

                    int oldIdx = selectedIntrinsicItemIdx;

                    // This logic is currently being handled by MainActivity.executeAction()
                    //selectIntrinsicItem(idx);

                    navDrawerLayout.closeDrawer(GravityCompat.START);
                    onCloseDrawer();

                    if(listener != null) {
                        listener.onIntrinsicItemChanged(oldIdx, idx);
                    }
                }
            });
        }
    }

    public void init(final Activity activity, DrawerLayout navDrawerLayout, int intrinsicItemIdx, NavigationDrawerListener listener) {
        this.tracker = ((Application) activity.getApplication()).getTracker();

        this.navDrawerLayout = navDrawerLayout;
        this.listener = listener;
        this.navDrawerToggle = new ActionBarDrawerToggle(activity, navDrawerLayout, R.string.open_drawer, R.string.close_drawer) {
            @Override
            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);
                activity.invalidateOptionsMenu();
                onCloseDrawer();
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                activity.invalidateOptionsMenu();
                onOpenDrawer();
            }
        };
        navDrawerLayout.setDrawerListener(navDrawerToggle);

        selectIntrinsicItem(intrinsicItemIdx);
    }

    public void updateLocationsDisplayIcons() {
        int displayIdx = Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0"));
        for(int i = 0; i < locationsDisplayIcons.length; ++i) {
            int attr;
            if(displayIdx == i) {
                attr = locationsDisplaySelectedAttrs[i];
            }
            else {
                attr = locationsDisplayAttrs[i];
            }

            // Set the icon as selected or non-selected.
            TypedValue selectedBackgroundValue = new TypedValue();
            getContext().getTheme().resolveAttribute(attr, selectedBackgroundValue, true);
            locationsDisplayIcons[i].setImageResource(selectedBackgroundValue.resourceId);
        }
    }

    public int getSelectedIntrinsicItemIdx() {
        return selectedIntrinsicItemIdx != null ? selectedIntrinsicItemIdx : -1;
    }

    public void onCloseDrawer() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(Strings.PREF_IS_DRAWER_OPEN, false);
        editor.apply();
    }

    public void onOpenDrawer() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(Strings.PREF_IS_DRAWER_OPEN, true);
        editor.apply();
    }

    public boolean isDrawerOpen() {
        return preferences.getBoolean(Strings.PREF_IS_DRAWER_OPEN, false);
    }

    public void selectIntrinsicItem(int itemIdx) {
        // Remove the current selection, if there is one.
        if(selectedIntrinsicItemIdx != null) {
            setIntrinsicItemBackground(intrinsicItems[selectedIntrinsicItemIdx], R.attr.viewSelectorDrawable);

            TypedValue imageValue = new TypedValue();
            getContext().getTheme().resolveAttribute(intrinsicItemAttrs[selectedIntrinsicItemIdx], imageValue, true);
            intrinsicItemIcons[selectedIntrinsicItemIdx].setBackgroundResource(imageValue.resourceId);
            intrinsicItemTexts[selectedIntrinsicItemIdx].setTextColor(defaultTextColor);
        }

        // Set the new selection.
        selectedIntrinsicItemIdx = itemIdx;

        setIntrinsicItemBackground(intrinsicItems[itemIdx], R.attr.navDrawerSelectedDrawable);

        TypedValue selectedImageValue = new TypedValue();
        getContext().getTheme().resolveAttribute(intrinsicItemSelectedAttrs[itemIdx], selectedImageValue, true);
        intrinsicItemIcons[itemIdx].setBackgroundResource(selectedImageValue.resourceId);

        intrinsicItemTexts[itemIdx].setTextColor(getResources().getColor(R.color.nav_drawer_selected_text_color));
    }

    protected void setIntrinsicItemBackground(View view, int backgroundAttr) {
        // On Android 4.4, there appears to be a bug where padding is lost when the background is changed.
        // To work around this, we save the padding and then restore it after setting the background.
        //
        // http://stackoverflow.com/questions/5890379/setbackgroundresource-discards-my-xml-layout-attributes
        int lpad = view.getPaddingLeft(),
            tpad = view.getPaddingTop(),
            rpad = view.getPaddingRight(),
            bpad = view.getPaddingBottom();

        TypedValue val = new TypedValue();
        getContext().getTheme().resolveAttribute(backgroundAttr, val, true);
        view.setBackgroundResource(val.resourceId);
        view.setPadding(lpad, tpad, rpad, bpad);
    }

    public boolean switchDisplay(int newDisplayIdx) {
        int oldDisplayIdx = Integer.valueOf(preferences.getString(Strings.PREF_LOCATIONS_VIEW, "0"));
        if(oldDisplayIdx == newDisplayIdx) {
            return false;
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(Strings.PREF_LOCATIONS_VIEW, Integer.toString(newDisplayIdx));
        editor.commit();

        navDrawerLayout.closeDrawer(GravityCompat.START);
        onCloseDrawer();
        updateLocationsDisplayIcons();

        if(listener != null) {
            listener.onLocationsDisplayChanged(oldDisplayIdx, newDisplayIdx);
        }

        return true;
    }

    public ActionBarDrawerToggle getActionBarToggle() {
        return navDrawerToggle;
    }

    public interface NavigationDrawerListener {
        void onLocationsDisplayChanged(int oldDisplayIdx, int newDisplayIdx);
        void onIntrinsicItemChanged(int oldIdx, int newIdx);
    }

}
