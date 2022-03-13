/*
 * Copyright 2014 Wouter Dullaert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sndurkin.locationscout.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapter that adds support for multiple swipe actions to your ListView
 *
 * Created by wdullaer on 04.06.14.
 */
public class SwipeActionAdapter extends DecoratorAdapter implements SwipeActionTouchListener.ActionCallbacks {

    private ListView listView;
    private SwipeActionTouchListener touchListener;
    protected SwipeActionListener swipeActionListener;
    private float swipeThresholdFraction = 0.25f;

    protected HashMap<SwipeDirection, Integer> backgroundResIds = new HashMap<>();

    public SwipeActionAdapter(BaseAdapter baseAdapter){
        super(baseAdapter);
    }

    @Override
    public View getView(final int position, final View convertView, final ViewGroup parent) {
        SwipeViewGroup output = (SwipeViewGroup) convertView;
        if(output == null) {
            output = new SwipeViewGroup(parent.getContext());
            for(Map.Entry<SwipeDirection, Integer> entry : backgroundResIds.entrySet()) {
                output.addBackground(View.inflate(parent.getContext(), entry.getValue(), null), entry.getKey());
            }
            output.setSwipeTouchListener(touchListener);
        }

        output.setContentView(super.getView(position, output.getContentView(), output));

        return output;
    }

    /**
     * SwipeActionTouchListener.ActionCallbacks callback
     * We just link it through to our own interface
     *
     * @param position the position of the item that was swiped
     * @param direction the direction in which the swipe has happened
     * @return boolean indicating whether the item has actions
     */
    @Override
    public boolean hasActions(int position, SwipeDirection direction){
        return swipeActionListener != null && swipeActionListener.hasActions(position, direction);
    }

    /**
     * SwipeActionTouchListener.ActionCallbacks callback
     * We just link it through to our own interface
     */
    @Override
    public void onActionClicked(int position, int actionResId) {
        if(swipeActionListener != null) {
            swipeActionListener.onActionClicked(position, actionResId);
        }
    }

    /**
     * Set the fraction of the View Width that needs to be swiped before it is counted as a normal swipe
     *
     * @param swipeThresholdFraction float between 0 and 1
     */
    @SuppressWarnings("unused")
    public SwipeActionAdapter setSwipeThresholdFraction(float swipeThresholdFraction) {
        if(swipeThresholdFraction < 0 || swipeThresholdFraction > 1) {
            throw new IllegalArgumentException("Must be a float between 0 and 1");
        }
        this.swipeThresholdFraction = swipeThresholdFraction;
        if(listView != null) {
            touchListener.setSwipeThresholdFraction(swipeThresholdFraction);
        }
        return this;
    }

    /**
     * We need the ListView to be able to modify its OnTouchListener.
     *
     * @param listView the ListView to which the adapter will be attached
     * @return A reference to the current instance so that commands can be chained
     */
    public SwipeActionAdapter setListView(ListView listView) {
        touchListener = new SwipeActionTouchListener(listView, this);
        touchListener.setSwipeThresholdFraction(swipeThresholdFraction);

        this.listView = listView;
        this.listView.setOnTouchListener(touchListener);
        this.listView.setClipChildren(false);

        return this;
    }

    public SwipeActionTouchListener getTouchListener() {
        return touchListener;
    }

    /**
     * Getter that is just here for completeness
     *
     * @return the current ListView
     */
    @SuppressWarnings("unused")
    public AbsListView getListView(){
        return listView;
    }

    /**
     * Add a background image for a certain callback. The key for the background must be one of the
     * directions from the SwipeDirections class.
     *
     * @param key the identifier of the callback for which this resource should be shown
     * @param resId the resource Id of the background to add
     * @return A reference to the current instance so that commands can be chained
     */
    public SwipeActionAdapter addBackground(SwipeDirection key, int resId){
        if(SwipeDirection.getAllDirections().contains(key)) backgroundResIds.put(key,resId);
        return this;
    }

    /**
     * Set the listener for swipe events
     *
     * @param mSwipeActionListener class listening to swipe events
     * @return A reference to the current instance so that commands can be chained
     */
    public SwipeActionAdapter setSwipeActionListener(SwipeActionListener mSwipeActionListener){
        this.swipeActionListener = mSwipeActionListener;
        return this;
    }

    /**
     * Interface that listeners of swipe events should implement
     */
    public interface SwipeActionListener {
        boolean hasActions(int position, SwipeDirection direction);
        void onActionClicked(int position, int actionResId);
    }
}