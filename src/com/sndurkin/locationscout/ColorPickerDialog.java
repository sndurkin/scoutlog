package com.sndurkin.locationscout;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.sndurkin.locationscout.util.UIUtils;

public class ColorPickerDialog extends AlertDialog {

    private HuePicker huePicker;
    private SatValPicker satValPicker;
    private View currentColorView;
    private View newColorView;

    public ColorPickerDialog(Context context, int currentColor) {
        super(context);

        getWindow().setFormat(PixelFormat.RGBA_8888);

        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.color_picker_dialog, null);
        setView(view);
        setTitle(R.string.dialog_color_picker_title);

        huePicker = (HuePicker) view.findViewById(R.id.hue_picker);
        satValPicker = (SatValPicker) view.findViewById(R.id.sat_val_picker);
        currentColorView = view.findViewById(R.id.current_color);
        newColorView = view.findViewById(R.id.new_color);

        // Set the current color on the views.
        float[] hsv = new float[3];
        Color.colorToHSV(currentColor, hsv);
        huePicker.setHue(hsv[0]);
        satValPicker.setHSV(hsv);
        currentColorView.setBackgroundColor(currentColor);

        // Set up the color selection listeners.
        huePicker.setOnHueSelectedListener(new HuePicker.OnHueSelectedListener() {
            @Override
            public void onSelected(float hue) {
                satValPicker.setHue(hue);
            }
        });
        satValPicker.setOnColorSelectedListener(new SatValPicker.OnColorSelectedListener() {
            @Override
            public void onSelected(int color, String hexVal) {
                newColorView.setBackgroundColor(color);
            }
        });
    }

    public int getColor() {
        return satValPicker.getColor();
    }

}