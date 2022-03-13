package com.sndurkin.locationscout;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageButton;

import com.sndurkin.locationscout.util.PhotoLoader;

public class DetailPhotoImageButton extends ImageButton {

    private Context context;

    public DetailPhotoImageButton(Context context) {
        this(context, null);
    }

    public DetailPhotoImageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
    }

    public DetailPhotoImageButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.context = context;
    }

    public void setPhoto(PhotoInfo photo) {
        PhotoLoader.loadIntoImageView(context, photo, this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The height = width / 1.78 to get a fixed 16:9 aspect ratio.
        super.onMeasure(widthMeasureSpec, (int) (widthMeasureSpec / 1.78));
        setMeasuredDimension(getMeasuredWidth(), (int) (getMeasuredWidth() / 1.78));
    }

}
