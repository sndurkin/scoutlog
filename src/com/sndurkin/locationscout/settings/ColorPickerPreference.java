package com.sndurkin.locationscout.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.sndurkin.locationscout.ColorPickerDialog;
import com.sndurkin.locationscout.R;

public class ColorPickerPreference extends Preference {

    private SharedPreferences preferences;
    private ImageView colorPreviewImage;

    private ColorPickerDialog colorPickerDialog;

    public ColorPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ColorPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ColorPickerPreference(Context context) {
        super(context);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);

        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.color_picker_preference, parent, false);

        TextView titleText = (TextView) view.findViewById(R.id.title);
        titleText.setText(getTitle());

        colorPreviewImage = (ImageView) view.findViewById(R.id.color_preview);
        updateColorPreview();

        return view;
    }

    protected void updateColorPreview() {
        ((GradientDrawable) colorPreviewImage.getBackground()).setColor(getColor());
    }

    protected int getColor() {
        return preferences.getInt(getKey(), Color.RED);
    }

    @Override
    protected void onClick() {
        colorPickerDialog = new ColorPickerDialog(getContext(), getColor());
        colorPickerDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getContext().getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                colorPickerDialog.dismiss();
            }
        });
        colorPickerDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getContext().getString(R.string.use_default), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if(callChangeListener(0)) {
                    SharedPreferences.Editor edit = preferences.edit();
                    edit.remove(getKey());
                    edit.commit();

                    updateColorPreview();
                }
            }
        });
        colorPickerDialog.setButton(AlertDialog.BUTTON_POSITIVE, getContext().getString(R.string.save), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final int newTagColor = colorPickerDialog.getColor();
                if (callChangeListener(newTagColor)) {
                    SharedPreferences.Editor edit = preferences.edit();
                    edit.putInt(getKey(), newTagColor);
                    edit.commit();

                    updateColorPreview();
                }
            }
        });
        colorPickerDialog.show();
    }

    public ColorPickerDialog getColorPickerDialog() {
        return colorPickerDialog;
    }

}
