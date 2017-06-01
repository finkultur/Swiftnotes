package com.moonpi.swiftnotes;

import android.content.IntentSender;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.DriveResource;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;


public abstract class BaseDriveActivity extends AppCompatActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener
{
    private GoogleApiClient mGoogleApiClient;
    private static final int REQUEST_CODE_RESOLUTION = 3;
    private static final String TAG = "swiftnotes";

    /**
     * Queries Google Drive for all files with fileName, and simply opens the first one.
       Sets given resultCallback as callback.
     * @param fileName Name of file
     * @param resultCallback Callback function on result
     */
    public void getFile(String fileName,
                        final ResultCallback<DriveApi.DriveContentsResult> resultCallback) {
        Query query = new Query.Builder()
                .addFilter(Filters.eq(SearchableField.TITLE, fileName))
                .build();
        Drive.DriveApi.query(mGoogleApiClient, query)
                .setResultCallback(new ResultCallback<DriveApi.MetadataBufferResult>() {
                    @Override
                    public void onResult(@NonNull DriveApi.MetadataBufferResult result) {
                        if (!result.getStatus().isSuccess()) {
                            // Error
                            Log.e(TAG, "Could not access files on Google Drive.");
                            return;
                        }
                        DriveId did = result.getMetadataBuffer().get(0).getDriveId();
                        DriveFile driveFile = did.asDriveFile();
                        driveFile.open(getGoogleApiClient(), DriveFile.MODE_READ_ONLY, null)
                                .setResultCallback(resultCallback);
                    }
                });
    }

    /**
     * Upload file to Google Drive.
     * Sorry about the nested callbacks :D
     * @param str Data to be uploaded
     * @param fileName Name of file
     * @param mimetype MIME-type of file
     */
    public void uploadFile(final String str, final String fileName, final String mimetype) {
        final MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(fileName)
                .setMimeType(mimetype)
                .setStarred(false).build();

        Drive.DriveApi.newDriveContents(mGoogleApiClient)
                .setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {
                    @Override
                    public void onResult(@NonNull DriveApi.DriveContentsResult result) {
                        if (!result.getStatus().isSuccess()) {
                            Log.i(TAG, "Failed to create new contents.");
                            return;
                        }
                        Log.i(TAG, "New contents created.");

                        DriveContents driveContents = result.getDriveContents();
                        Writer writer = new OutputStreamWriter(driveContents.getOutputStream());
                        try {
                            writer.write(str);
                            writer.close();
                        } catch (IOException e) {
                            Log.e(TAG, e.getMessage());
                        }

                        // Create a file on root folder
                        Drive.DriveApi.getRootFolder(mGoogleApiClient)
                                .createFile(mGoogleApiClient, changeSet, driveContents)
                                .setResultCallback(new ResultCallback<DriveFolder.DriveFileResult>() {
                                    @Override
                                    public void onResult(@NonNull DriveFolder.DriveFileResult result) {
                                        if (!result.getStatus().isSuccess()) {
                                            Log.i(TAG, "Error while trying to create the file");
                                            showToast(R.string.toast_backup_failed);
                                            return;
                                        }
                                        DriveId currentDriveId = result.getDriveFile().getDriveId();
                                        Log.i(TAG, "Created a file with content: " + currentDriveId);
                                        trashOldFiles(currentDriveId, fileName);
                                    }
                                });
                    }
                });
    }

    /**
     * Trash old files on Google Drive matching filename, except given DriveId.
     * @param currentDriveId DriveId of file to be excused
     * @param fileName name of file(s) to be removed
     */
    public void trashOldFiles(final DriveId currentDriveId, final String fileName) {
        Query query = new Query.Builder()
                .addFilter(Filters.eq(SearchableField.TITLE, fileName))
                .build();
        Drive.DriveApi.query(mGoogleApiClient, query)
                .setResultCallback(new ResultCallback<DriveApi.MetadataBufferResult>() {
                    @Override
                    public void onResult(@NonNull DriveApi.MetadataBufferResult result) {
                        // Trash all files except given DriveId
                        for (Metadata md : result.getMetadataBuffer()) {
                            DriveId did = md.getDriveId();
                            if (!did.equals(currentDriveId)) {
                                DriveResource driveResource = did.asDriveResource();
                                if (!md.isTrashed()) {
                                    driveResource.trash(mGoogleApiClient);
                                }
                            }
                        }
                        result.release();
                        Log.i(TAG, "Trashed old files.");
                    }
                });
    }

    /**
     * Called when activity gets invisible. Connection to Drive service needs to
     * be disconnected as soon as an activity is invisible.
     */
    @Override
    protected void onPause() {
        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        if (mGoogleApiClient == null) {
            // Create the API client and bind it to an instance variable.
            // We use this instance as the callback for connection and connection
            // failures.
            // Since no account name is passed, the user is prompted to choose.
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Drive.API)
                    .addScope(Drive.SCOPE_FILE)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }
        // Connect the client. Once connected, onConnected() is called.
        Log.i(TAG, "Connect GoogleApiClient");
        mGoogleApiClient.connect();
        super.onResume();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.i(TAG, "GoogleApiClient connected.");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.i(TAG, "GoogleApiClient connection suspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        // Called whenever the API client fails to connect.
        Log.i(TAG, "GoogleApiClient connection failed: " + result.toString());
        if (!result.hasResolution()) {
            // Show the localized error dialog.
            GoogleApiAvailability
                    .getInstance()
                    .getErrorDialog(this, result.getErrorCode(), 0).show();
            return;
        }
        // The failure has a resolution. Resolve it.
        // Called typically when the app is not yet authorized, and an
        // authorization
        // dialog is displayed to the user.
        try {
            result.startResolutionForResult(this, REQUEST_CODE_RESOLUTION);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Exception while starting resolution activity", e);
        }
    }

    /**
     * Getter for the {@code GoogleApiClient}.
     */
    public GoogleApiClient getGoogleApiClient() {
        return mGoogleApiClient;
    }

    /**
     * Shows a toast with the given message.
     */
    public void showToast(int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

}
