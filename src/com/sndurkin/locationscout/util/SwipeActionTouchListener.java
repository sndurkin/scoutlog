/*
* Copyright 2013 Google Inc
* Copyright 2014 Wouter Dullaert
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.sndurkin.locationscout.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.widget.AdapterView;
import android.widget.ListView;

import com.crashlytics.android.core.CrashlyticsCore;
import com.sndurkin.locationscout.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link View.OnTouchListener} that makes the list items in a {@link ListView}
 * dismissable. {@link ListView} is given special treatment because by default it handles touches
 * for its list items... i.e. it's in charge of drawing the pressed state (the list selector),
 * handling list item clicks, etc.
 *
 * <p>Example usage:</p>
 *
 * <pre>
 * SwipeActionTouchListener touchListener =
 * new SwipeActionTouchListener(
 * listView,
 * new SwipeActionTouchListener.OnDismissCallback() {
 * public void onDismiss(ListView listView, int[] reverseSortedPositions) {
 * for (int position : reverseSortedPositions) {
 * adapter.remove(adapter.getItem(position));
 * }
 * adapter.notifyDataSetChanged();
 * }
 * });
 * listView.setOnTouchListener(touchListener);
 * listView.setOnScrollListener(touchListener.makeScrollListener());
 * </pre>
 *
 * <p>This class Requires API level 12 or later due to use of {@link
 * ViewPropertyAnimator}.</p>
 */
public class SwipeActionTouchListener implements View.OnTouchListener {

    // Cached ViewConfiguration and system-wide constant values
    private int slop;
    private int minFlingVelocity;
    private int maxFlingVelocity;
    private long animationTime;

    // Fixed properties
    private ListView listView;
    private ActionCallbacks callbacks;
    private int viewWidth = 1; // 1 and not 0 to prevent dividing by zero
    private float swipeThresholdFraction = 0.25f;
    private float farSwipeFraction = 0.5f;

    // Transient properties
    private float downX;
    private float downY;
    private boolean swiping;
    private int swipingSlop;
    private VelocityTracker velocityTracker;
    private int downPosition;
    private View downView;
    private SwipeViewGroup downViewGroup;
    private View backgroundView;
    private View action;
    private boolean isPaused;

    // Represents the position of element in the ListView that's currently
    // swiped open. THERE CAN BE ONLY ONE!
    private int openPosition = AdapterView.INVALID_POSITION;

    private SwipeDirection direction = SwipeDirection.DIRECTION_NEUTRAL;

    /**
     * The callback interface used by {@link SwipeActionTouchListener} to inform its client
     * about a successful dismissal of one or more list item positions.
     */
    public interface ActionCallbacks {
        /**
         * Called to determine whether the given position can be dismissed.
         *
         * @param position the position of the item that was swiped
         * @param direction the direction in which the swipe happened
         * @return boolean indicating whether the item has actions
         */
        boolean hasActions(int position, SwipeDirection direction);

        void onActionClicked(int position, int actionResId);
    }

    /**
     * Constructs a new swipe-to-dismiss touch listener for the given list view.
     *
     * @param listView The list view whose items should be dismissable.
     * @param callbacks The callback to trigger when the user has indicated that she would like to
     * dismiss one or more list items.
     */
    public SwipeActionTouchListener(ListView listView, ActionCallbacks callbacks) {
        ViewConfiguration vc = ViewConfiguration.get(listView.getContext());
        slop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 16;
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        animationTime = listView.getContext().getResources().getInteger(android.R.integer.config_shortAnimTime);
        this.listView = listView;
        this.callbacks = callbacks;
    }

    /**
     * Enables or disables (pauses or resumes) watching for swipe-to-dismiss gestures.
     *
     * @param enabled Whether or not to watch for gestures.
     */
    public void setEnabled(boolean enabled) {
        isPaused = !enabled;
        if(!enabled) {
            closeCurrentlyOpenView();
        }
    }

    public boolean isEnabled() {
        return !isPaused;
    }

    /**
     * Set the fraction of the View Width that needs to be swiped before it is counted as a normal swipe
     *
     * @param swipeThresholdFraction float between 0 and 1, should be equal to or less than farSwipeFraction
     */
    protected void setSwipeThresholdFraction(float swipeThresholdFraction) {
        this.swipeThresholdFraction = swipeThresholdFraction;
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (viewWidth < 2) {
            viewWidth = listView.getWidth();
        }

        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (isPaused) {
                    return false;
                }

                Pair<Integer, View> childPair = findChildFromMotionEvent(motionEvent);
                if(childPair.first != AdapterView.INVALID_POSITION) {
                    try {
                        final int position = childPair.first;
                        downViewGroup = (SwipeViewGroup) childPair.second;
                        downView = downViewGroup.getContentView();
                        backgroundView = downViewGroup.getBackground(SwipeDirection.DIRECTION_NORMAL_LEFT);
                        action = backgroundView.findViewById(R.id.navigate_button);
                        action.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                callbacks.onActionClicked(position, v.getId());
                            }
                        });

                        if(openPosition == position && userClickedInsideActionArea(motionEvent, action)) {
                            // Let the button handle it.
                            return false;
                        }
                    }
                    catch(Exception e) {
                        downView = childPair.second;
                    }
                }

                if (downView != null) {
                    downX = motionEvent.getRawX() - downView.getX();
                    downY = motionEvent.getRawY() - downView.getY();
                    downPosition = listView.getPositionForView(downView);
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(motionEvent);

                    if(openPosition == downPosition) {
                        // Prevent click from opening location when swipe actions are exposed.
                        MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
                        cancelEvent.setAction(MotionEvent.ACTION_CANCEL | (motionEvent.getActionIndex() << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                        listView.onTouchEvent(cancelEvent);
                        cancelEvent.recycle();
                        return true;
                    }
                }

                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker == null || isPaused) {
                    break;
                }

                backgroundView.setVisibility(View.VISIBLE);
                velocityTracker.addMovement(motionEvent);
                float deltaX = motionEvent.getRawX() - downX;
                float deltaY = motionEvent.getRawY() - downY;
                if (!swiping && Math.abs(deltaX) > slop && Math.abs(deltaY) < Math.abs(deltaX) / 2) {
                    swiping = true;
                    swipingSlop = (deltaX > 0 ? slop : -slop);

                    listView.requestDisallowInterceptTouchEvent(true);

                    // Cancel ListView's touch (un-highlighting the item)
                    MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL | (motionEvent.getActionIndex() << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                    listView.onTouchEvent(cancelEvent);
                    cancelEvent.recycle();

                    closeCurrentlyOpenView();
                }

                if (swiping) {
                    direction = deltaX > 0 ? SwipeDirection.DIRECTION_NORMAL_RIGHT : SwipeDirection.DIRECTION_NORMAL_LEFT;
                    if(callbacks.hasActions(downPosition, direction)) {
                        downView.setTranslationX(deltaX - swipingSlop);
                        /*if(mFadeOut) {
                            downView.setAlpha(Math.max(0f, Math.min(1f, 1f - 2f * Math.abs(deltaX) / viewWidth)));
                        }*/
                        listView.invalidate();
                        return true;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (velocityTracker == null) {
                    if(downView != null && downView.getTranslationX() != 0) {
                        downView.animate()
                                .translationX(0)
                                .setDuration(animationTime)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        if(openPosition == downPosition) {
                                            openPosition = AdapterView.INVALID_POSITION;
                                        }
                                        backgroundView.setVisibility(View.INVISIBLE);
                                    }
                                });
                    }

                    break;
                }

                float deltaX = motionEvent.getRawX() - downX;
                velocityTracker.addMovement(motionEvent);
                velocityTracker.computeCurrentVelocity(1000);
                float velocityX = velocityTracker.getXVelocity();
                float absVelocityX = Math.abs(velocityX);
                float absVelocityY = Math.abs(velocityTracker.getYVelocity());

                boolean shouldOpen = false;
                boolean openRight = false;
                if(callbacks.hasActions(downPosition, direction)) {
                    if(Math.abs(deltaX) > (action.getWidth() / 2) && swiping) {
                        shouldOpen = true;
                        openRight = deltaX > 0;
                    }
                    else if (minFlingVelocity <= absVelocityX
                            && absVelocityX <= maxFlingVelocity
                            && absVelocityY < absVelocityX && swiping) {
                        // dismiss only if flinging in the same direction as dragging
                        shouldOpen = (velocityX < 0) == (deltaX < 0);
                        openRight = velocityTracker.getXVelocity() > 0;
                    }
                }

                final int downPosition = this.downPosition;
                if(shouldOpen && downPosition != ListView.INVALID_POSITION) {
                    // dismiss
                    //final SwipeDirection direction = this.direction;
                    downView.animate()
                            .translationX(openRight ? action.getWidth() : -action.getWidth())
                            .setDuration(animationTime)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    openPosition = downPosition;
                                }
                            });
                }
                else {
                    // cancel
                    downView.animate()
                            .translationX(0)
                            .setDuration(animationTime)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if(openPosition == downPosition) {
                                        openPosition = AdapterView.INVALID_POSITION;
                                    }
                                    backgroundView.setVisibility(View.INVISIBLE);
                                }
                            });
                }

                if(velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                downX = 0;
                downY = 0;
                downView = null;
                this.downPosition = ListView.INVALID_POSITION;
                swiping = false;
                direction = SwipeDirection.DIRECTION_NEUTRAL;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (velocityTracker == null) {
                    break;
                }

                if (downView != null && swiping) {
                    // cancel
                    downView.animate()
                            .translationX(0)
                            .setDuration(animationTime)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if(openPosition == downPosition) {
                                        openPosition = AdapterView.INVALID_POSITION;
                                    }
                                    backgroundView.setVisibility(View.INVISIBLE);
                                }
                            });
                }
                velocityTracker.recycle();
                velocityTracker = null;
                downX = 0;
                downY = 0;
                downView = null;
                downPosition = ListView.INVALID_POSITION;
                swiping = false;
                direction = SwipeDirection.DIRECTION_NEUTRAL;
                break;
            }
        }
        return false;
    }

    private void closeCurrentlyOpenView() {
        if(openPosition != AdapterView.INVALID_POSITION && openPosition != downPosition) {
            View currentlyOpenView = listView.getChildAt(openPosition);
            if(currentlyOpenView != null && currentlyOpenView instanceof SwipeViewGroup) {
                ((SwipeViewGroup) currentlyOpenView).getContentView().animate()
                        .translationX(0)
                        .setDuration(animationTime)
                        .setListener(null);
            }
        }
    }


    @NonNull
    private Pair<Integer, View> findChildFromMotionEvent(MotionEvent motionEvent) {
        int[] listViewCoords = new int[2];
        listView.getLocationOnScreen(listViewCoords);
        int x = (int) motionEvent.getRawX() - listViewCoords[0];
        int y = (int) motionEvent.getRawY() - listViewCoords[1];

        Rect rect = new Rect();
        int childCount = listView.getChildCount();
        for (int i = Math.max(0, listView.getFirstVisiblePosition() - 1); i < childCount; i++) {
            View child = listView.getChildAt(i);
            child.getHitRect(rect);
            if (rect.contains(x, y)) {
                return new Pair<>(i, child);
            }
        }

        return new Pair<>(AdapterView.INVALID_POSITION, null);
    }

    private boolean userClickedInsideActionArea(MotionEvent motionEvent, View actionArea) {
        int[] listViewCoords = new int[2];
        listView.getLocationOnScreen(listViewCoords);
        int x = (int) motionEvent.getRawX() - listViewCoords[0];
        int y = (int) motionEvent.getRawY() - listViewCoords[1];

        Rect rect = new Rect();
        downViewGroup.getHitRect(rect);

        x -= rect.left;
        y -= rect.top;
        actionArea.getHitRect(rect);
        return rect.contains(x, y);
    }

}