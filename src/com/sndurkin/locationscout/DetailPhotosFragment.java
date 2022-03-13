package com.sndurkin.locationscout;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.text.format.DateUtils;
import android.view.ActionMode;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.crashlytics.android.core.CrashlyticsCore;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.android.gms.maps.model.LatLng;
import com.google.api.services.drive.Drive;
import com.sndurkin.locationscout.settings.SyncSettingsFragment;
import com.sndurkin.locationscout.storage.DatabaseHelper;
import com.sndurkin.locationscout.storage.DatabaseQueryListener;
import com.sndurkin.locationscout.storage.DatabaseQueryResult;
import com.sndurkin.locationscout.storage.LocationModel;
import com.sndurkin.locationscout.storage.LocationModelChanges;
import com.sndurkin.locationscout.util.FileUtils;
import com.sndurkin.locationscout.util.PhotoFileCreateException;
import com.sndurkin.locationscout.util.RequestCodes;
import com.sndurkin.locationscout.util.Strings;
import com.sndurkin.locationscout.util.UIUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class DetailPhotosFragment extends Fragment implements DriveEnabledScreen {

    private View view;

    private Tracker tracker;
    private Drive driveService;

    private Long locationId;

    private boolean shouldAddInlineAlertMessage;

    private PhotoInfoList photoInfoList = new PhotoInfoList();

    private GridView photosGrid;
    private PhotoGridAdapter photoGridAdapter;
    private Button addFromCameraButton;
    private Button addFromGalleryButton;

    private String currentCameraPhotoPath;

    private Snackbar snackbar;
    private int tentativelyDeletedPhotoIdx;
    private PhotoInfo tentativelyDeletedPhotoInfo;

    private LocationModelChanges locationChanges;

    private StoragePermissionRequestType storagePermissionRequestType;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = setView(inflater, R.layout.detail_photos_fragment, container);

        new CreateDriveServiceAsyncTask(getActivity(), this).execute();

        tracker = Application.getInstance().getTracker();

        addFromCameraButton = (Button) view.findViewById(R.id.detail_photo_add_camera);
        addFromGalleryButton = (Button) view.findViewById(R.id.detail_photo_add_gallery);
        photosGrid = (GridView) view.findViewById(R.id.detail_photos_ct);

        snackbar = (Snackbar) view.findViewById(R.id.snackbar);

        addFromCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCameraForPhoto();
            }
        });
        addFromGalleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhotoFromGallery();
            }
        });

        photosGrid.setEmptyView(view.findViewById(R.id.empty_photos_view));

        if(savedInstanceState != null) {
            locationId = savedInstanceState.getLong(Strings.LOCATION_ID);
            currentCameraPhotoPath = savedInstanceState.getString("currentCameraPhotoPath");
            ArrayList<PhotoInfo> photoInfoArrayList = savedInstanceState.getParcelableArrayList("photoList");
            photoInfoList = new PhotoInfoList(photoInfoArrayList);
            shouldAddInlineAlertMessage = savedInstanceState.getBoolean("shouldAddInlineAlertMessage");
            locationChanges = savedInstanceState.getParcelable(Strings.PARAM_LOCATION_CHANGES);
            refreshImagesView();
        }
        else {
            Bundle extras = getActivity().getIntent().getExtras();
            if(extras != null) {
                locationId = extras.getLong(Strings.LOCATION_ID);
                loadPhotos(locationId);
            }
            else {
                photoInfoList = new PhotoInfoList();
            }
            locationChanges = new LocationModelChanges();

            if(locationId == null) {
                refreshImagesView();
            }
        }

        return view;
    }

    protected View setView(LayoutInflater inflater, int layoutId, ViewGroup container) {
        if (view != null) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }

        try {
            view = inflater.inflate(layoutId, container, false);
        }
        catch (InflateException e) {
            // Map is already there, so return view as it is.
            CrashlyticsCore.getInstance().logException(e);
        }

        return view;
    }

    @Override
    public Drive getDriveService() {
        return driveService;
    }

    @Override
    public void setDriveService(Drive driveService) {
        if(!isAdded()) {
            return;
        }

        this.driveService = driveService;
        Glide.get(getActivity()).register(String.class, InputStream.class, new DriveModelLoader.Factory(driveService, true));
        refreshImagesView();
    }

    @Override
    public void onPause() {
        snackbar.hide(false);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle instanceState) {
        super.onSaveInstanceState(instanceState);

        instanceState.putLong(Strings.LOCATION_ID, locationId);
        instanceState.putString("currentCameraPhotoPath", currentCameraPhotoPath);
        instanceState.putParcelableArrayList("photoList", photoInfoList.getList());
        instanceState.putBoolean("shouldAddInlineAlertMessage", shouldAddInlineAlertMessage);
        instanceState.putParcelable(Strings.PARAM_LOCATION_CHANGES, locationChanges);
    }

    public void openCameraForPhoto() {
        if(!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA) &&
           !getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.no_camera_found_title)
                    .setMessage(R.string.no_camera_found_message)
                    .setNeutralButton(android.R.string.ok, null)
                    .show();
            return;
        }

        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            try {
                String timestamp  = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                try {
                    File photoDir = FileUtils.getPhotoDirChecked(getActivity());
                    File photoFile = File.createTempFile("IMG_"+ timestamp, ".jpg", photoDir);
                    currentCameraPhotoPath = photoFile.getAbsolutePath();

                    Intent photoCaptureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    photoCaptureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
                    startActivityForResult(photoCaptureIntent, RequestCodes.CAPTURE_IMAGE);
                }
                catch(PhotoFileCreateException e) {
                    SyncSettingsFragment.showPhotoFileCreateErrorDialog(getActivity(), FileUtils.getPhotoDir(getActivity()).getAbsolutePath(), null);
                }
            }
            catch(IOException e) {
                CrashlyticsCore.getInstance().logException(e);
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.camera_file_not_created_title)
                        .setMessage(R.string.camera_file_not_created_message)
                        .setNeutralButton(android.R.string.ok, null)
                        .show();
            }
        }
        else {
            storagePermissionRequestType = StoragePermissionRequestType.OPEN_CAMERA;
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    RequestCodes.PERMISSION_REQUEST_STORAGE);
        }
    }

    public LocationModelChanges getLocationChanges() {
        return locationChanges;
    }

    public void addPhotoFromGallery() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            Intent photoPickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
            photoPickerIntent.setType("image/*");
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                photoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            photoPickerIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            startActivityForResult(photoPickerIntent, RequestCodes.SELECT_IMAGE);
        }
        else {
            storagePermissionRequestType = StoragePermissionRequestType.OPEN_GALLERY;
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    RequestCodes.PERMISSION_REQUEST_STORAGE
            );
        }
    }

    // This method adds the PhotoInfo instance to the database and refreshes the UI.
    protected void addPhotoInfos(final List<PhotoInfo> photoInfos) {
        snackbar.expire();

        if(photoInfoList.isEmpty()) {
            // We're adding the first photo for the location, so save that it has been changed.
            locationChanges.photo = true;
        }

        int idx = photoInfoList.getNextSortNum();
        for(PhotoInfo photoInfo : photoInfos) {
            photoInfo.sortNum = idx++;
        }
        DatabaseHelper.getInstance(getActivity()).savePhotoInfos(photoInfos, locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                for (int i = 0; i < photoInfos.size(); ++i) {
                    photoInfos.get(i).setLocalId(result.ids.get(i));
                }
                photoInfoList.addAll(photoInfos);
                refreshImagesView();
            }
        });
    }

    enum StoragePermissionRequestType {
        OPEN_CAMERA,
        OPEN_GALLERY
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == RequestCodes.PERMISSION_REQUEST_STORAGE) {
            if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if(storagePermissionRequestType == StoragePermissionRequestType.OPEN_CAMERA) {
                    openCameraForPhoto();
                }
                else if(storagePermissionRequestType == StoragePermissionRequestType.OPEN_GALLERY) {
                    addPhotoFromGallery();
                }
                storagePermissionRequestType = null;
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case RequestCodes.SELECT_IMAGE:
                if(resultCode == Activity.RESULT_OK) {
                    // Add the photo(s) to the detail page.
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        ClipData clipData = data.getClipData();
                        if(clipData != null) {
                            shouldAddInlineAlertMessage = photoInfoList.isEmpty() && clipData.getItemCount() > 0;

                            boolean atLeastOnePhotoNotLoaded = false;
                            List<PhotoInfo> photoInfos = new ArrayList<>();
                            for(int i = 0; i < clipData.getItemCount(); ++i) {
                                Uri photoUri = clipData.getItemAt(i).getUri();
                                String filePath = FileUtils.getFilePathFromUri(photoUri, true);
                                if(filePath == null) {
                                    atLeastOnePhotoNotLoaded = true;
                                    continue;
                                }

                                String path = Uri.parse(filePath).getPath();
                                photoInfos.add(new PhotoInfo(path));
                            }
                            addPhotoInfos(photoInfos);

                            if(atLeastOnePhotoNotLoaded) {
                                new AlertDialog.Builder(getActivity())
                                        .setTitle(R.string.photo_not_loaded_title)
                                        .setMessage(R.string.photos_not_loaded_message)
                                        .setNeutralButton(android.R.string.ok, null)
                                        .show();
                            }

                            return;
                        }
                    }

                    if(data.getData() != null) {
                        shouldAddInlineAlertMessage = photoInfoList.isEmpty();
                        String filePath = FileUtils.getFilePathFromUri(data.getData(), true);
                        if(filePath == null) {
                            new AlertDialog.Builder(getActivity())
                                    .setTitle(R.string.photo_not_loaded_title)
                                    .setMessage(R.string.photo_not_loaded_message)
                                    .setNeutralButton(android.R.string.ok, null)
                                    .show();
                            return;
                        }
                        String path = Uri.parse(filePath).getPath();
                        addPhotoInfos(Collections.singletonList(new PhotoInfo(path)));
                    }
                }
                break;
            case RequestCodes.CAPTURE_IMAGE:
                if(resultCode == Activity.RESULT_OK) {
                    // Add the photo to the gallery.
                    FileUtils.scanGalleryPhoto(getActivity(), new File(currentCameraPhotoPath));

                    // Add the photo to the detail page.
                    addPhotoInfos(Arrays.asList(new PhotoInfo(currentCameraPhotoPath)));
                }
                break;
            case RequestCodes.EDIT_NOTE_FOR_PHOTO_ACTIVITY:
                if(resultCode == Activity.RESULT_OK && data != null) {
                    final String note = data.getStringExtra(Strings.PARAM_NOTE);
                    if(note != null) {
                        // We use a temporary PhotoInfo instance just to write to the database.
                        final PhotoInfo photoInfoToWrite = new PhotoInfo();
                        photoInfoToWrite.setLocalId(data.getLongExtra(Strings.PHOTO_ID, -1L));
                        photoInfoToWrite.notes = note;
                        DatabaseHelper.getInstance(getActivity()).savePhotoInfos(Arrays.asList(photoInfoToWrite), locationId, new DatabaseQueryListener() {
                            @Override
                            public void onQueryExecuted(DatabaseQueryResult result) {
                                PhotoInfo photoInfo = photoInfoList.findById(photoInfoToWrite.getLocalId());
                                if (photoInfo != null) {
                                    photoInfo.notes = note;
                                    photoGridAdapter.notifyDataSetChanged();
                                }
                            }
                        });
                    }
                }
                break;
        }
    }

    protected void loadPhotos(long locationId) {
        DatabaseHelper.getInstance(getActivity()).fetchPhotosForLocation(locationId, new DatabaseQueryListener() {
            @Override
            public void onQueryExecuted(DatabaseQueryResult result) {
                if(!isAdded()) {
                    return;
                }

                photoInfoList = new PhotoInfoList();
                photoInfoList.addAll(result.cursor);
                refreshImagesView();
            }
        });
    }

    protected void deletePhoto(final int position) {
        tracker.send(new HitBuilders.EventBuilder()
                .setCategory(Strings.CAT_UI_ACTION)
                .setAction(Strings.ACT_CLICK_BUTTON)
                .setLabel(Strings.LBL_DELETE_PHOTO)
                .build());

        snackbar.expire();

        final boolean origPhotoLocationChanges = locationChanges.photo;
        if(position == 0) {
            // We're deleting the first photo for the location, so save that it has been changed.
            locationChanges.photo = true;
        }

        tentativelyDeletedPhotoIdx = position;
        tentativelyDeletedPhotoInfo = photoInfoList.remove(position);
        refreshImagesView();

        Snackbar.ShowConfig config = snackbar.new ShowConfig();
        config.text = getString(R.string.photo_deleted_snackbar);
        config.listener = snackbar.new Listener() {
            @Override
            public void onExpired() {
                photoInfoList.sort();
                DatabaseHelper.getInstance(getActivity()).deletePhotoInfo(tentativelyDeletedPhotoInfo, photoInfoList, locationId, null);
                tentativelyDeletedPhotoInfo = null;
            }

            @Override
            public void onButtonClicked() {
                tracker.send(new HitBuilders.EventBuilder()
                        .setCategory(Strings.CAT_UI_ACTION)
                        .setAction(Strings.ACT_CLICK_BUTTON)
                        .setLabel(Strings.LBL_DELETE_PHOTO_UNDO)
                        .build());

                // Undo deleting this photo.
                locationChanges.photo = origPhotoLocationChanges;
                photoInfoList.add(tentativelyDeletedPhotoIdx, tentativelyDeletedPhotoInfo);
                tentativelyDeletedPhotoInfo = null;
                refreshImagesView();
            }
        };
        snackbar.show(config);
    }

    protected void refreshImagesView() {
        // I tried calling the following methods instead of setAdapter():
        //
        //      photosGrid.invalidateViews();
        //      photoGridAdapter.notifyDataSetChanged();
        //      photosGrid.forceLayout();
        //
        // but they didn't initiate a refresh of an image that was added
        // immediately after deleting one; the deleted image was still
        // displayed.
        if(photoGridAdapter == null) {
            photoGridAdapter = new PhotoGridAdapter();
        }
        photosGrid.setAdapter(photoGridAdapter);
    }

    // This class is used to display locations in a grid view.
    private class PhotoGridAdapter extends BaseAdapter {

        private LayoutInflater inflater;

        public PhotoGridAdapter() {
            inflater = LayoutInflater.from(getActivity());
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final ViewGroup photoCt;
            PhotoHolder h;
            if(convertView != null) {
                photoCt = (ViewGroup) convertView;
                h = (PhotoHolder) photoCt.getTag();
            }
            else {
                photoCt = (ViewGroup) inflater.inflate(R.layout.detail_photo_ct, null);
                h = new PhotoHolder(photoCt);
                photoCt.setTag(h);
            }

            final PhotoInfo photoInfo = photoInfoList.get(position);
            final PhotoHolder holder = h;

            holder.photoButton.setPhoto(photoInfo);
            holder.photoButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getActivity(), GalleryActivity.class);
                    intent.putExtra("photoInfoList", photoInfoList.getList());
                    intent.putExtra("photoInfoIdx", position);
                    startActivity(intent);
                }
            });

            UIUtils.addHintFunctionalityToView(getActivity(), holder.shareButton);
            UIUtils.addHintFunctionalityToView(getActivity(), holder.deleteButton);

            holder.shareButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!photoInfo.existsLocally()) {
                        tracker.send(new HitBuilders.EventBuilder()
                                .setCategory(Strings.CAT_UI_ACTION)
                                .setAction(Strings.ACT_CLICK_MENU_ITEM)
                                .setLabel(Strings.LBL_SHARE_PHOTO_NO_PHOTO)
                                .build());

                        Toast.makeText(getActivity(), R.string.no_photo_for_sharing, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    tracker.send(new HitBuilders.EventBuilder()
                            .setCategory(Strings.CAT_UI_ACTION)
                            .setAction(Strings.ACT_CLICK_MENU_ITEM)
                            .setLabel(Strings.LBL_SHARE_PHOTO)
                            .build());

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("image/*");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text));
                    shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(photoInfo.getLocalFile()));
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_photo)));
                }
            });
            holder.deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deletePhoto(position);
                }
            });

            Date photoDate = FileUtils.getDateForPhoto(photoInfo.path);
            String photoDateStr = formatPhotoDate(photoInfo, photoDate);
            if(photoDateStr == null) {
                holder.dateCt.setVisibility(View.GONE);
            }
            else {
                holder.dateCt.setVisibility(View.VISIBLE);
                holder.dateText.setText(photoDateStr);
            }

            View.OnClickListener notesClickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getActivity(), EditNoteActivity.class);
                    intent.putExtra(Strings.PARAM_NOTE, holder.notesText.getText().toString());
                    intent.putExtra(Strings.PHOTO_ID, photoInfo.getLocalId());
                    startActivityForResult(intent, RequestCodes.EDIT_NOTE_FOR_PHOTO_ACTIVITY);
                }
            };

            holder.notesText.setText(photoInfo.notes);
            holder.notesText.setOnClickListener(notesClickListener);
            if(photoInfo.notes != null && !photoInfo.notes.isEmpty()) {
                holder.notesToolbar.setVisibility(View.VISIBLE);
                holder.notesEditBtn.setOnClickListener(notesClickListener);
                holder.notesEditBtn.setText(R.string.edit_note);

                // This logic is used to hide the text selection mode/popup when the user
                // touches the toolbar.
                holder.notesText.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(final ActionMode mode, Menu menu) {
                        holder.notesToolbar.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mode.finish();
                            }
                        });
                        return true;
                    }

                    @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

                    @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }

                    @Override public void onDestroyActionMode(ActionMode mode) { holder.notesToolbar.setOnClickListener(null); }
                });
            }
            else {
                holder.notesToolbar.setVisibility(View.GONE);
            }

            if(position == 0) {
                addInlineAlertMessageIfApplicable(photoCt, photoInfo, photoDate);
            }
            else {
                final LinearLayout inlineAlertMessage = (LinearLayout) photoCt.findViewById(R.id.set_info_from_photo_message);
                inlineAlertMessage.setVisibility(View.GONE);
            }

            return photoCt;
        }

        class PhotoHolder {

            DetailPhotoImageButton photoButton;
            ImageButton shareButton;
            ImageButton deleteButton;
            ViewGroup dateCt;
            TextView dateText;
            TextView notesText;
            View notesToolbar;
            Button notesEditBtn;

            public PhotoHolder(ViewGroup photoCt) {
                photoButton = (DetailPhotoImageButton) photoCt.findViewById(R.id.detail_photo);
                shareButton = (ImageButton) photoCt.findViewById(R.id.detail_photo_share);
                deleteButton = (ImageButton) photoCt.findViewById(R.id.detail_photo_delete);
                dateCt = (ViewGroup) photoCt.findViewById(R.id.detail_photo_date_ct);
                dateText = (TextView) photoCt.findViewById(R.id.detail_photo_date);
                notesText = (TextView) photoCt.findViewById(R.id.detail_photo_notes);
                notesToolbar = photoCt.findViewById(R.id.detail_photo_notes_toolbar);
                notesEditBtn = (Button) photoCt.findViewById(R.id.card_edit_btn);
            }
        }

        @Override
        public int getCount() {
            return photoInfoList.size();
        }

        @Override
        public Object getItem(int position) {
            return photoInfoList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        // If the first photo added has a location and it's more than 30 meters from the current set location,
        // display a non-intrusive message asking the user to change to the photo's location (do the same for
        // the photo's date if it does not equal the location's date).
        protected void addInlineAlertMessageIfApplicable(final ViewGroup photoCt, final PhotoInfo photoInfo, Date photoDate) {
            if(!isAdded()) {
                return;
            }
            if(!shouldAddInlineAlertMessage) {
                return;
            }

            try {
                boolean askToSetLocation = false;
                boolean askToSetDate = false;

                // Check to see if the photo was taken on a date other than the current date for the location.
                if(photoDate != null) {
                    Calendar photoCal = Calendar.getInstance();
                    photoCal.setTime(photoDate);

                    Date currentDate = ((DetailActivity) getActivity()).getDate();
                    Calendar currentCal = Calendar.getInstance();
                    currentCal.setTime(currentDate);

                    if(currentCal.get(Calendar.YEAR) != photoCal.get(Calendar.YEAR) ||
                       currentCal.get(Calendar.DAY_OF_YEAR) != photoCal.get(Calendar.DAY_OF_YEAR)) {
                        askToSetDate = true;
                    }
                }

                // Check to see if the photo has latitude/longitude data set and if it differs significantly
                // from the current location.
                LatLng latLng = FileUtils.getLatLngForPhoto(photoInfo.path);
                if(latLng != null) {
                    LocationInfo locationInfo = ((DetailActivity) getActivity()).getLocationInfo();
                    if(locationInfo == null) {
                        // There's no current location available on the device.
                        askToSetLocation = true;
                    }
                    else {
                        float[] distanceResults = new float[1];
                        Location.distanceBetween(latLng.latitude, latLng.longitude, locationInfo.location.latitude, locationInfo.location.longitude, distanceResults);
                        if(distanceResults[0] > 30) {
                            askToSetLocation = true;
                        }
                    }
                }

                if(askToSetLocation || askToSetDate) {
                    // Set the message text depending on what data we'll be setting on the location from the photo.
                    int msgId;
                    if(askToSetLocation && askToSetDate) {
                        msgId = R.string.set_location_and_date_from_photo;
                    }
                    else if(askToSetLocation) {
                        msgId = R.string.set_location_from_photo;
                    }
                    else {
                        msgId = R.string.set_date_from_photo;
                    }
                    TextView setInfoFromPhotoText = (TextView) photoCt.findViewById(R.id.set_info_from_photo_text);
                    setInfoFromPhotoText.setText(msgId);

                    // Make the inline alert message visible.
                    final LinearLayout inlineAlertMessage = (LinearLayout) photoCt.findViewById(R.id.set_info_from_photo_message);
                    inlineAlertMessage.setVisibility(View.VISIBLE);

                    final boolean askToSetLocationFinal = askToSetLocation;
                    final boolean askToSetDateFinal = askToSetDate;
                    final LatLng latLngFinal = latLng;
                    final Date photoDateFinal = photoDate;

                    Button yesButton = (Button) photoCt.findViewById(R.id.set_info_from_photo_yes_btn);
                    yesButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_SET_INFO_FROM_PHOTO_YES)
                                    .build());

                            LocationModel locationModel = new LocationModel(locationId);
                            if(askToSetLocationFinal) {
                                locationModel.setLocationInfo(new LocationInfo(latLngFinal, null, true));
                            }
                            if(askToSetDateFinal) {
                                locationModel.setDate(new java.sql.Date(photoDateFinal.getTime()));
                            }

                            DatabaseHelper.getInstance(getActivity()).saveLocation(locationModel, new DatabaseQueryListener() {
                                @Override
                                public void onQueryExecuted(DatabaseQueryResult result) {
                                    if(askToSetLocationFinal) {
                                        ((DetailActivity) getActivity()).setLocation(latLngFinal);
                                    }
                                    if(askToSetDateFinal) {
                                        ((DetailActivity) getActivity()).setDate(photoDateFinal);
                                    }
                                    Toast.makeText(getActivity(), R.string.info_set_from_photo, Toast.LENGTH_SHORT).show();
                                }
                            });

                            inlineAlertMessage.setVisibility(View.GONE);
                            shouldAddInlineAlertMessage = false;
                        }
                    });

                    Button noButton = (Button) photoCt.findViewById(R.id.set_info_from_photo_no_btn);
                    noButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            tracker.send(new HitBuilders.EventBuilder()
                                    .setCategory(Strings.CAT_UI_ACTION)
                                    .setAction(Strings.ACT_CLICK_BUTTON)
                                    .setLabel(Strings.LBL_SET_INFO_FROM_PHOTO_NO)
                                    .build());
                            inlineAlertMessage.setVisibility(View.GONE);
                            shouldAddInlineAlertMessage = false;
                        }
                    });
                }
            }
            catch(IOException e) {
                CrashlyticsCore.getInstance().logException(e);
            }
        }

        private String formatPhotoDate(PhotoInfo photoInfo, Date photoDate) {
            if(photoDate != null) {
                return DateUtils.formatDateTime(
                        getActivity(),
                        photoDate.getTime(),
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
            }

            if(photoInfo.existsLocally()) {
                try {
                    ExifInterface exif = new ExifInterface(photoInfo.path);
                    return exif.getAttribute(ExifInterface.TAG_DATETIME);
                }
                catch(IOException e) {
                    // Do nothing, as a crash would've already been reported by FileUtils.getDateForPhoto().
                }
            }

            return null;
        }

    }

}
