package com.sndurkin.locationscout;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.sndurkin.locationscout.integ.ExportService;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;


public class CSVConfigurationActivity extends AppCompatActivity {

    protected Tracker tracker;
    protected SharedPreferences preferences;

    protected CheckBox includeHeaderCheckbox;
    protected ListView columnsListView;
    protected FloatingActionButton saveButton;

    protected ArrayList<Integer> placeIds;

    protected CSVFieldList fieldList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setTheme(UIUtils.getCurrentTheme(this));

        super.onCreate(savedInstanceState);

        tracker = ((Application) getApplication()).getTracker();
        tracker.setScreenName(Strings.CSV_EXPORT_CONFIGURATION_VIEW);
        tracker.send(new HitBuilders.ScreenViewBuilder().build());

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        setContentView(R.layout.csv_configuration_activity);
        setTitle(R.string.menu_export_csv);
        setupActionBar();

        includeHeaderCheckbox = (CheckBox) findViewById(R.id.include_csv_header_checkbox);
        columnsListView = (ListView) findViewById(R.id.csv_columns_list);
        saveButton = (FloatingActionButton) findViewById(R.id.save_button);

        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            placeIds = extras.getIntegerArrayList(Strings.LOCATION_IDS);
            fieldList = new CSVFieldList(preferences.getString(Strings.PREF_CSV_CONFIG_FIELD_NAMES, null));
            includeHeaderCheckbox.setChecked(preferences.getBoolean(Strings.PREF_CSV_CONFIG_INCLUDE_HEADER, false));
        }

        columnsListView.setAdapter(new ListAdapter());
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_EXPORT_PLACES_CSV_CONFIG)
                        .build());

                SharedPreferences.Editor editor = preferences.edit();
                editor.putString(Strings.PREF_CSV_CONFIG_FIELD_NAMES, fieldList.toString());
                editor.putBoolean(Strings.PREF_CSV_CONFIG_INCLUDE_HEADER, includeHeaderCheckbox.isChecked());
                editor.apply();

                Intent intent = new Intent(CSVConfigurationActivity.this, ExportService.class);
                intent.putIntegerArrayListExtra(Strings.LOCATION_IDS, placeIds);
                intent.putStringArrayListExtra(Strings.FIELD_NAMES, fieldList.toStringList());
                intent.putExtra(Strings.INCLUDE_HEADER, includeHeaderCheckbox.isChecked());
                intent.putExtra(Strings.EXPORT_TYPE, RequestCodes.EXPORT_TYPE_CSV);
                startService(intent);
                finish();
            }
        });
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        placeIds = savedInstanceState.getIntegerArrayList(Strings.LOCATION_IDS);
        ArrayList<CSVField> list = savedInstanceState.getParcelableArrayList("fieldList");
        fieldList = new CSVFieldList(list);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putIntegerArrayList(Strings.LOCATION_IDS, placeIds);
        outState.putParcelableArrayList("fieldList", fieldList);
    }

    private void setupActionBar() {
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.close_dark);
    }

    private class CSVFieldList extends ArrayList<CSVField> {

        private static final String SERIALIZATION_SEPARATOR = "~~";

        public CSVFieldList(ArrayList<CSVField> list) {
            super(12);
            this.addAll(list);
        }

        public CSVFieldList(String serializedList) {
            super(12);

            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_TITLE));
            if(preferences.getBoolean(Strings.PREF_ENABLE_DATES, true)) {
                this.add(new CSVField(Strings.CSV_EXPORT_HEADER_DATE));
            }
            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_ADDRESS));
            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_LATITUDE));
            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_LONGITUDE));
            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_COORDINATES));
            this.add(new CSVField(Strings.CSV_EXPORT_HEADER_NOTES));

            if(serializedList != null) {
                String[] fieldNames = serializedList.split(SERIALIZATION_SEPARATOR);
                for(String fieldName : fieldNames) {
                    for(CSVField field : this) {
                        if(field.name.equals(fieldName)) {
                            field.selected = true;
                            break;
                        }
                    }
                }
            }

            Collections.sort(this, new Comparator<CSVField>() {
                @Override
                public int compare(CSVField field1, CSVField field2) {
                    if(field1.selected && !field2.selected) {
                        return -1;
                    }
                    else if(field2.selected && !field1.selected) {
                        return 1;
                    }

                    return 0;
                }
            });
        }

        public String toString() {
            String serializedList = "";
            for(int i = 0; i < size(); ++i) {
                if(!get(i).selected) {
                    continue;
                }

                if(i > 0) {
                    serializedList += SERIALIZATION_SEPARATOR;
                }
                serializedList += get(i).name;
            }
            return serializedList;
        }

        public ArrayList<String> toStringList() {
            ArrayList<String> list = new ArrayList<>(fieldList.size());
            for(CSVField field : fieldList) {
                if(field.selected) {
                    list.add(field.name);
                }
            }
            return list;
        }

    }

    private class ListAdapter extends ArrayAdapter<CSVField> {

        private LayoutInflater inflater;

        public ListAdapter() {
            super(CSVConfigurationActivity.this, R.layout.csv_column_item_view);
            inflater = getLayoutInflater();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View itemView;
            if(convertView != null) {
                itemView = convertView;
            }
            else {
                itemView = inflater.inflate(R.layout.csv_column_item_view, parent, false);
            }

            final CSVField field = getItem(position);
            if(field != null) {
                final CheckBox checkbox = (CheckBox) itemView.findViewById(R.id.csv_column_item_checkbox);
                checkbox.setText(field.getDisplayName(CSVConfigurationActivity.this));
                checkbox.setChecked(field.selected);
                itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        checkbox.toggle();
                        field.selected = checkbox.isChecked();
                        fieldList.remove(field);

                        if(field.selected) {
                            int firstUnselectedIdx = fieldList.size();
                            for(int i = 0; i < fieldList.size(); ++i) {
                                if(!fieldList.get(i).selected) {
                                    firstUnselectedIdx = i;
                                    break;
                                }
                            }

                            fieldList.add(firstUnselectedIdx, field);
                        }
                        else {
                            fieldList.add(field);
                        }

                        notifyDataSetChanged();
                    }
                });
            }

            return itemView;
        }

        @Override
        public int getCount() {
            return fieldList != null ? fieldList.size() : 0;
        }

        @Override
        public long getItemId(int position) {
            CSVField field = getItem(position);
            return field != null ? field.name.hashCode() : -1;
        }

        @Override
        public CSVField getItem(int position) {
            if(getCount() == 0) {
                return null;
            }
            return fieldList.get(position);
        }

    }

}
