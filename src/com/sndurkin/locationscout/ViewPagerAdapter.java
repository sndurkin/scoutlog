package com.sndurkin.locationscout;

import android.support.v4.view.PagerAdapter;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

public class ViewPagerAdapter extends PagerAdapter {

    protected List<ViewGroup> pages = new ArrayList<ViewGroup>();

    public void addView(ViewGroup container) {
        pages.add(container);
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public int getCount() {
        return pages.size();
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        if(pages.size() > position) {
            container.addView(pages.get(position));
            return pages.get(position);
        }
        else {
            return super.instantiateItem(container, position);
        }
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }

}
