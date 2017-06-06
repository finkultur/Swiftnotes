package com.moonpi.swiftnotes;

import com.google.android.gms.drive.DriveId;

import java.util.Date;

public class Backup {

    private String title;
    private DriveId driveId;
    private Date date;

    public Backup(String title, DriveId driveId, Date date) {
        this.title = title;
        this.driveId = driveId;
        this.date = date;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DriveId getDriveId() {
        return driveId;
    }

    public void setDriveId(DriveId driveId) {
        this.driveId = driveId;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

}
