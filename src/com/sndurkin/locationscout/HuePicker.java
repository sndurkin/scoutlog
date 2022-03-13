package com.sndurkin.locationscout;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

import com.sndurkin.locationscout.util.UIUtils;


public class HuePicker extends SeekBar {

    private OnHueSelectedListener onHueSelectedListener;

    private Paint bkgrndPaint;
    private LinearGradient linearGradient;

    // marginPx represents both the margin size for the draw area
    // as well as the radius of the thumb. They are equivalent because
    // it allows the thumb to always be visible, even when on the edge
    // of the draw area.
    private static final int MARGIN_DP = 8;
    private int marginPx;

    private int[] thumbColors;
    private Paint thumbPaint;

    public HuePicker(Context context) {
        super(context);
        init(context);
    }

    public HuePicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(final Context context) {
        marginPx = UIUtils.dpToPx(context, MARGIN_DP);

        setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fireOnHueSelectedListener(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()) {
            return false;
        }

        switch(event.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                setProgress((int) (getMax() * event.getY() / getHeight()));
                invalidate();
                break;
        }
        return true;
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getMeasuredWidth();
        int height = getMeasuredHeight() - marginPx;

        if(bkgrndPaint == null) {
            bkgrndPaint = new Paint();
            thumbPaint = new Paint();

            thumbColors = new int[] {
                    getResources().getColor(R.color.color_picker_thumb_1),
                    getResources().getColor(R.color.color_picker_thumb_2)
            };
        }

        // Draw the background.
        int[] colors = new int[] { Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED};
        linearGradient = new LinearGradient(0.f, marginPx, 0.f, height, colors, null, Shader.TileMode.CLAMP);
        bkgrndPaint.setShader(linearGradient);
        canvas.drawRect(0.f, marginPx, width, height, bkgrndPaint);

        // Draw the thumb.
        float thumbProgressRatio = (float) getProgress() / getMax();
        int thumbContrainedHeight = getMeasuredHeight() - (marginPx * 2);
        int thumbY = (int) (thumbProgressRatio * thumbContrainedHeight) + marginPx;
        int thumbX = getMeasuredWidth() / 2;
        thumbPaint.setShader(new RadialGradient(thumbX, thumbY, marginPx, thumbColors, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(thumbX, thumbY, marginPx, thumbPaint);
    }

    public void setHue(float hue) {
        setProgress((int) hue);
    }

    public void fireOnHueSelectedListener(final float hue) {
        if(onHueSelectedListener != null) {
            onHueSelectedListener.onSelected(hue);
        }
    }

    public void setOnHueSelectedListener(OnHueSelectedListener onHueSelectedListener){
        this.onHueSelectedListener = onHueSelectedListener;
    }

    public interface OnHueSelectedListener {
        void onSelected(float hue);
    }
}