package com.sndurkin.locationscout;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.sndurkin.locationscout.util.PhotoLoader;

import java.io.File;

// This is a square image view which displays right-aligned text at the bottom. It can also
// optionally display a right-aligned date and a mini color preview image over the image.
public class GalleryImageView extends RelativeLayout {

    private Context context;

    private ImageView imageView;
    private ImageView mapMarkerView;
    private TextView mainText;

    public GalleryImageView(Context context) {
        this(context, null);
    }

    public GalleryImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        imageView = (ImageView) findViewById(R.id.gallery_image);
        mapMarkerView = (ImageView) findViewById(R.id.gallery_map_marker);
        mainText = (TextView) findViewById(R.id.gallery_main_text);
    }

    public void setPhoto(PhotoInfo photo) {
        PhotoLoader.loadIntoImageView(context, photo, imageView);
    }

    @SuppressWarnings("deprecation")
    public void setMapMarker(String iconPath, int color) {
        boolean mapMarkerSet = false;
        if(iconPath != null) {
            File iconFile = new File(iconPath);
            if(iconFile.exists()) {
                mapMarkerView.setVisibility(VISIBLE);
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    mapMarkerView.setBackground(null);
                }
                else {
                    mapMarkerView.setBackgroundDrawable(null);
                }
                Glide.with(context)
                        .load(Uri.fromFile(iconFile))
                        .into(mapMarkerView);
                mapMarkerSet = true;
            }
        }
        else if(color != 0) {
            mapMarkerView.setVisibility(VISIBLE);
            GradientDrawable colorPreviewDrawable = (GradientDrawable) getResources().getDrawable(R.drawable.mini_color_preview_with_border);
            colorPreviewDrawable.setColor(color);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                mapMarkerView.setBackground(colorPreviewDrawable);
            }
            else {
                mapMarkerView.setBackgroundDrawable(colorPreviewDrawable);
            }
            mapMarkerView.setImageDrawable(null);
            mapMarkerSet = true;
        }

        if(!mapMarkerSet) {
            mapMarkerView.setVisibility(GONE);
        }
    }

    public void setText(String text) {
        mainText.setText(text);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
    }

}
