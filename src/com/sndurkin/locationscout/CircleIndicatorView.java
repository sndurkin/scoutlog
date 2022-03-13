package com.sndurkin.locationscout;

import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import static android.support.v4.view.ViewPager.OnPageChangeListener;

public class CircleIndicatorView extends LinearLayout implements OnPageChangeListener {

    private final static int DEFAULT_INDICATOR_WIDTH_DP = 5;

    private ViewPager viewPager;
    private OnPageChangeListener pageChangeListener;

    private int indicatorWidth;
    private int indicatorHeight;
    private int indicatorMargin;

    private AnimatorSet animationOut;
    private AnimatorSet animationIn;

    private int currentPos = 0;

    private SharedPreferences preferences;
    private int indicatorShapeResId;

    public CircleIndicatorView(Context context) {
        super(context);
        init(context);
    }

    public CircleIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER);

        indicatorWidth = UIUtils.dpToPx(context, DEFAULT_INDICATOR_WIDTH_DP);
        indicatorHeight = UIUtils.dpToPx(context, DEFAULT_INDICATOR_WIDTH_DP);
        indicatorMargin = UIUtils.dpToPx(context, DEFAULT_INDICATOR_WIDTH_DP);

        animationOut = (AnimatorSet) AnimatorInflater.loadAnimator(context, R.animator.scale_with_alpha);
        animationOut.setInterpolator(new LinearInterpolator());
        animationIn = (AnimatorSet) AnimatorInflater.loadAnimator(context, R.animator.scale_with_alpha);
        animationIn.setInterpolator(new ReverseLinearInterpolator());

        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if("0".equals(preferences.getString(Strings.PREF_THEME, "0"))) {
            indicatorShapeResId = R.drawable.circle_indicator_shape_light;
        }
        else {
            indicatorShapeResId = R.drawable.circle_indicator_shape_dark;
        }
    }

    public void setViewPager(ViewPager viewPager) {
        this.viewPager = viewPager;
        createIndicators(viewPager);
        this.viewPager.setOnPageChangeListener(this);
    }

    public void setOnPageChangeListener(OnPageChangeListener onPageChangeListener) {
        pageChangeListener = onPageChangeListener;
        viewPager.setOnPageChangeListener(this);
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        if(pageChangeListener != null) {
            pageChangeListener.onPageScrolled(position, positionOffset, positionOffsetPixels);
        }
    }

    @Override
    public void onPageSelected(int position) {
        if(pageChangeListener != null) {
            pageChangeListener.onPageSelected(position);
        }

        animationIn.setTarget(getChildAt(currentPos));
        animationIn.start();

        animationOut.setTarget(getChildAt(position));
        animationOut.start();

        currentPos = position;
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        if(pageChangeListener != null) {
            pageChangeListener.onPageScrollStateChanged(state);
        }
    }

    private void createIndicators(ViewPager viewPager) {
        removeAllViews();
        int count = viewPager.getAdapter().getCount();
        if(count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            View indicatorView = new View(getContext());
            indicatorView.setBackgroundResource(indicatorShapeResId);
            addView(indicatorView, indicatorWidth, indicatorHeight);
            LayoutParams lp = (LayoutParams) indicatorView.getLayoutParams();
            lp.leftMargin = indicatorMargin;
            lp.rightMargin = indicatorMargin;
            indicatorView.setLayoutParams(lp);

            animationOut.setTarget(indicatorView);
            animationOut.start();
        }

        animationOut.setTarget(getChildAt(currentPos));
        animationOut.start();
    }

    class ReverseLinearInterpolator implements Interpolator {
        @Override
        public float getInterpolation(float value) {
            return Math.abs(1.0f - value);
        }
    }
}