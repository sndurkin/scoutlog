package com.sndurkin.locationscout;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// This class handles displaying/hiding an MD-style Snackbar or Toast. It's intended to be
// used within a RelativeLayout and will anchor itself to the bottom of the view, can optionally
// be slide in and out, and can display a message with an action button (for Snackbars),
// or just a message (for Toasts).
public class Snackbar extends LinearLayout implements Runnable {

    public static final long DEFAULT_TOAST_EXPIRE_TIME = 2000L;
    public static final long DEFAULT_SNACKBAR_EXPIRE_TIME = 3500L;
    public static final long LONG_SNACKBAR_EXPIRE_TIME = 5000L;

    private Listener listener;

    private TextView text;
    private TextView button;
    private ImageView closeIcon;

    private Handler handler;

    private Animation slideOutAnim;
    private Animation slideInAnim;
    private boolean showAfterHiding = false;
    private ShowConfig configForNextShow = null;

    public Snackbar(Context context) {
        super(context, null);
    }

    public Snackbar(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public Snackbar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        text = (TextView) findViewById(R.id.snackbar_text);
        button = (TextView) findViewById(R.id.snackbar_button);
        closeIcon = (ImageView) findViewById(R.id.snackbar_close_icon);

        slideOutAnim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_out);
        slideInAnim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_in);

        if(button != null) {
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (handler != null) {
                        //MiscUtils.logv("Snackbar button clicked, removing Handler");
                        handler.removeCallbacksAndMessages(null);
                        handler = null;
                    }
                    setVisibility(GONE);

                    if (listener != null) {
                        listener.onButtonClicked();
                    }
                }
            });
        }

        if(closeIcon != null) {
            closeIcon.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (handler != null) {
                        //MiscUtils.logv("Snackbar close icon clicked, removing Handler");
                        handler.removeCallbacksAndMessages(null);
                        handler = null;
                    }
                    setVisibility(GONE);

                    if(listener != null) {
                        listener.onClosed();
                    }
                }
            });
        }
    }

    public void show(final ShowConfig config) {
        //MiscUtils.logv("Snackbar.show()");
        if(slideOutAnim.hasStarted() && !slideOutAnim.hasEnded()) {
            //MiscUtils.logv("  - slide out animation started, waiting until it's finished");
            //MiscUtils.logv("Slide out animation started, waiting for next show");
            showAfterHiding = true;
            configForNextShow = config;
            return;
        }

        // Expire if old Snackbar is still active.
        expire();

        listener = config.listener;
        text.setText(config.text);
        button.setText(config.buttonText != null ? config.buttonText : R.string.undo);
        button.setVisibility(config.showButton ? VISIBLE : GONE);
        closeIcon.setVisibility(config.showCloseIcon ? VISIBLE : GONE);
        if(!config.animate || getVisibility() == VISIBLE) {
            setVisibility(VISIBLE);
            if(listener != null) {
                listener.onShown();
            }

            handler = new Handler();
            handler.postDelayed(Snackbar.this, config.expireTime);
            return;
        }

        slideInAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) { }

            @Override
            public void onAnimationEnd(Animation animation) {
                if(listener != null) {
                    listener.onShown();
                }

                handler = new Handler();
                handler.postDelayed(Snackbar.this, config.expireTime);
            }

            @Override
            public void onAnimationRepeat(Animation animation) { }
        });
        setVisibility(VISIBLE);
        startAnimation(slideInAnim);
    }

    public void hide(boolean animate) {
        if(!animate) {
            setVisibility(GONE);
            if(listener != null) {
                listener.onHidden();
            }
            expire();
            return;
        }

        slideOutAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                //MiscUtils.logv("Snackbar.onAnimationEnd()");
                //MiscUtils.logv("  - expiring snackbar");
                setVisibility(GONE);
                if (listener != null) {
                    listener.onHidden();
                }
                expire();

                if (showAfterHiding) {
                    //MiscUtils.logv("  - showing after hiding");
                    showAfterHiding = false;
                    show(configForNextShow);
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        startAnimation(slideOutAnim);
    }

    public void expire() {
        if(handler != null) {
            //MiscUtils.logv("Snackbar.expire(), Handler not null");
            handler.removeCallbacksAndMessages(null);
            handler = null;

            if(listener != null) {
                listener.onExpired();
            }
        }
    }

    // This is used to execute the Handler after the specified amount of time.
    @Override
    public void run() {
        //MiscUtils.logv("Snackbar runnable executed");
        hide(true);
    }

    public class ShowConfig {
        public boolean animate = true;
        public boolean showButton = true;
        public boolean showCloseIcon = false;
        public Integer buttonText = null;
        public String text = null;
        public long expireTime = DEFAULT_SNACKBAR_EXPIRE_TIME;
        public Listener listener = null;

        public ShowConfig() { }
    }

    public abstract class Listener {
        public void onShown() {}
        public void onHidden() {}
        public void onExpired() {}
        public void onButtonClicked() {}
        public void onClosed() {}
    }

}
