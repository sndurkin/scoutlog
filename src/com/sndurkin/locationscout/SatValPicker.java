package com.sndurkin.locationscout;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.sndurkin.locationscout.util.UIUtils;


public class SatValPicker extends ViewGroup {

    private Context context;

    private OnColorSelectedListener onColorSelectedListener;

    // marginPx represents both the margin size for the draw area
    // as well as the radius of the thumb. They are equivalent because
    // it allows the thumb to always be visible, even when on the edge
    // of the draw area.
    private static final int MARGIN_DP = 8;
    private int marginPx;

    private Paint bkgrndPaint;
    private Shader vertShader;
    private float[] hsv = { 1.f, 1.f, 1.f };

    private Paint thumbPaint;
    private int[] thumbColors;

    private int width;
    private int height;

    private Float thumbX;
    private Float thumbY;

    private Float pendingSaturation;
    private Float pendingValue;

    public SatValPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SatValPicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    protected void init(Context context) {
        this.context = context;
        this.marginPx = UIUtils.dpToPx(context, MARGIN_DP);
        setWillNotDraw(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // Constrain to a square shape.
        if(widthSize < heightSize) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(widthSize, heightMode));
            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }
        else {
            super.onMeasure(MeasureSpec.makeMeasureSpec(heightSize, widthMode), heightMeasureSpec);
            setMeasuredDimension(getMeasuredHeight(), getMeasuredHeight());
        }

        width = getMeasuredWidth();
        height = getMeasuredWidth();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if(pendingSaturation != null) {
            setHSV(new float[] { hsv[0], pendingSaturation, pendingValue });
            pendingSaturation = pendingValue = null;
        }
        else if(thumbX == null) {
            moveThumb(width, 0);
        }
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        int drawWidth = width - marginPx;
        int drawHeight = height - marginPx;

        if(bkgrndPaint == null) {
            bkgrndPaint = new Paint();
            thumbPaint = new Paint();
            vertShader = new LinearGradient(marginPx, marginPx, marginPx, drawHeight, 0xffffffff, 0xff000000, Shader.TileMode.CLAMP);

            thumbColors = new int[] {
                    getResources().getColor(R.color.color_picker_thumb_1),
                    getResources().getColor(R.color.color_picker_thumb_2)
            };
        }

        // Draw the background.
        int color = Color.HSVToColor(hsv);
        Shader horizShader = new LinearGradient(marginPx, marginPx, drawWidth, marginPx, 0xffffffff, color, Shader.TileMode.CLAMP);
        ComposeShader shader = new ComposeShader(vertShader, horizShader, PorterDuff.Mode.MULTIPLY);
        bkgrndPaint.setShader(shader);
        canvas.drawRect(marginPx, marginPx, drawWidth, drawHeight, bkgrndPaint);

        // Draw the thumb.
        thumbPaint.setShader(new RadialGradient(thumbX, thumbY, marginPx, thumbColors, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(thumbX, thumbY, marginPx, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_MOVE:
                moveThumb(event.getX(), event.getY());
                invalidate();
                return true;
        }
        return false;
    }

    private void moveThumb(float x, float y) {
        thumbX = Math.min(Math.max(x, marginPx), width - marginPx);
        thumbY = Math.min(Math.max(y, marginPx), height - marginPx);
        fireOnColorSelectedListener();
    }

    public void setHue(float hue) {
        hsv[0] = hue;
        invalidate();
        fireOnColorSelectedListener();
    }

    public void setHSV(float[] hsv) {
        this.hsv[0] = hsv[0];
        invalidate();

        if(width > 0 && height > 0) {
            float x = hsv[1] * width;
            float y = height - (hsv[2] * height);
            moveThumb(x, y);
        }
        else {
            pendingSaturation = hsv[1];
            pendingValue = hsv[2];
        }
    }

    public int getColor() {
        float saturation = thumbX / (float) width;
        float value = (height - thumbY) / (float) height;
        return Color.HSVToColor(new float[] { hsv[0], saturation, value });
    }

    private void fireOnColorSelectedListener() {
        if(onColorSelectedListener != null) {
            int color = getColor();
            onColorSelectedListener.onSelected(color, "#" + Integer.toHexString(color));
        }
    }

    public void setOnColorSelectedListener(OnColorSelectedListener onColorSelectedListener) {
        this.onColorSelectedListener = onColorSelectedListener;
    }

    public interface OnColorSelectedListener {
        void onSelected(int color, String hexVal);
    }

}