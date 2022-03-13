package com.sndurkin.locationscout;

import com.google.api.services.drive.Drive;


public interface DriveEnabledScreen {

    void setDriveService(Drive drive);
    Drive getDriveService();

}
