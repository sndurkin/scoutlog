package com.sndurkin.locationscout;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;

// This class provides the following functionality:
//
//  1) Fires a listener whenever the user moves the map.
//  2) Allows the alpha property to be set on the My Location button.
public class CustomSupportMapFragment extends SupportMapFragment {

    private View myLocationButton;

    private OnMapMovedListener listener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View originalContentView = super.onCreateView(inflater, container, savedInstanceState);
        TouchableWrapper touchView = new TouchableWrapper(getActivity());
        touchView.addView(originalContentView);
        return touchView;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        myLocationButton = view.findViewWithTag("GoogleMapMyLocationButton");
    }

    public void setMyLocationButtonAlpha(float alpha) {
        if(myLocationButton != null) {
            myLocationButton.setAlpha(alpha);
        }
    }

    public void setOnMapMovedListener(OnMapMovedListener listener) {
        this.listener = listener;
    }

    interface OnMapMovedListener {
        void onMapMoved();
    }

    class TouchableWrapper extends FrameLayout {

        private float x;
        private float y;
        private static final double RADIUS = 100;

        public TouchableWrapper(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x = event.getX();
                    y = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    if(listener != null) {
                        double xdiff = Math.pow(x - event.getX(), 2);
                        double ydiff = Math.pow(y - event.getY(), 2);
                        if(Math.sqrt(xdiff + ydiff) > RADIUS) {
                            listener.onMapMoved();
                        }
                    }
                    break;
            }
            return super.dispatchTouchEvent(event);
        }

    }

}
