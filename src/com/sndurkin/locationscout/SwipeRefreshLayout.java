package com.sndurkin.locationscout;


import android.content.Context;
import android.util.AttributeSet;

// This class overrides SwipeRefreshLayout for a few things:
//
//  1) Allow a calling class to determine the return value for canChildScrollUp()
//  2) Provide a workaround for this bug: https://code.google.com/p/android/issues/detail?id=77712
//
public class SwipeRefreshLayout extends android.support.v4.widget.SwipeRefreshLayout {

    private OnChildScrollUpListener scrollUpListener;

    private boolean measured = false;
    private boolean preMeasureRefreshing = false;

    public interface OnChildScrollUpListener {
        boolean canChildScrollUp();
    }

    public SwipeRefreshLayout(Context context) {
        super(context);
    }
    public SwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Listener that controls if scrolling up is allowed to child views or not.
    public void setOnChildScrollUpListener(OnChildScrollUpListener listener) {
        scrollUpListener = listener;
    }

    @Override
    public boolean canChildScrollUp() {
        return scrollUpListener == null || scrollUpListener.canChildScrollUp();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (!measured) {
            measured = true;
            setRefreshing(preMeasureRefreshing);
        }
    }

    @Override
    public void setRefreshing(boolean refreshing) {
        if (measured) {
            super.setRefreshing(refreshing);
        }
        else {
            preMeasureRefreshing = refreshing;
        }
    }

}
