package com.moonpi.swiftnotes;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

import static com.moonpi.swiftnotes.DataUtils.BACKUP_FILE_NAME;
import static com.moonpi.swiftnotes.DataUtils.BACKUP_FOLDER_PATH;
import static com.moonpi.swiftnotes.DataUtils.NEW_NOTE_REQUEST;
import static com.moonpi.swiftnotes.DataUtils.NOTES_ARRAY_NAME;
import static com.moonpi.swiftnotes.DataUtils.NOTES_FILE_NAME;
import static com.moonpi.swiftnotes.DataUtils.NOTE_BODY;
import static com.moonpi.swiftnotes.DataUtils.NOTE_COLOUR;
import static com.moonpi.swiftnotes.DataUtils.NOTE_FAVOURED;
import static com.moonpi.swiftnotes.DataUtils.NOTE_FONT_SIZE;
import static com.moonpi.swiftnotes.DataUtils.NOTE_HIDE_BODY;
import static com.moonpi.swiftnotes.DataUtils.NOTE_REQUEST_CODE;
import static com.moonpi.swiftnotes.DataUtils.NOTE_TITLE;
import static com.moonpi.swiftnotes.DataUtils.retrieveData;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemClickListener,
        Toolbar.OnMenuItemClickListener, AbsListView.MultiChoiceModeListener,
        SearchView.OnQueryTextListener, ConnectionCallbacks, OnConnectionFailedListener {

    private static final String TAG = "swiftnotes";
    private static File localPath, backupPath;

    // Google Drive stuff
    private GoogleApiClient mGoogleApiClient;
    private static final int REQUEST_CODE_RESOLUTION = 3;
    private static final String DRIVE_FILE_NAME = "swiftnotes_current.json";

    // Layout components
    private static ListView listView;
    private ImageButton newNote;
    private TextView noNotes;
    private Toolbar toolbar;
    private MenuItem searchMenu;

    private static JSONArray notes; // Main notes array
    private static NoteAdapter adapter; // Custom ListView notes adapter

    // Array of selected positions for deletion
    public static ArrayList<Integer> checkedArray = new ArrayList<>();
    public static boolean deleteActive = false; // True if delete mode is active, false otherwise

    // For disabling long clicks, favourite clicks and modifying the item click pattern
    public static boolean searchActive = false;
    private ArrayList<Integer> realIndexesOfSearchResults; // To keep track of real indexes in searched notes

    private int lastFirstVisibleItem = -1; // Last first item seen in list view scroll changed
    private float newNoteButtonBaseYCoordinate; // Base Y coordinate of newNote button

    private AlertDialog cloudBackupCheckDialog, backupCheckDialog, cloudBackupOKDialog,
                        backupOKDialog, cloudRestoreCheckDialog, restoreCheckDialog,
                        cloudRestoreFailedDialog, restoreFailedDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize local file path and backup file path
        localPath = new File(getFilesDir() + "/" + NOTES_FILE_NAME);

        File backupFolder = new File(Environment.getExternalStorageDirectory() + BACKUP_FOLDER_PATH);

        if (DataUtils.isExternalStorageReadable()
                && DataUtils.isExternalStorageWritable()
                && !backupFolder.exists()) {
            if (!backupFolder.mkdir()) {
                Log.i(TAG, "Failed to create folder on external storage");
            }
        }

        backupPath = new File(backupFolder, BACKUP_FILE_NAME);

        // Android version >= 18 -> set orientation userPortrait
        if (Build.VERSION.SDK_INT >= 18)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);

        // Android version < 18 -> set orientation sensorPortrait
        else
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

        // Init notes array
        notes = new JSONArray();

        // Retrieve from local path
        JSONArray tempNotes = DataUtils.retrieveData(localPath);

        // If not null -> equal main notes to retrieved notes
        if (tempNotes != null)
            notes = tempNotes;

        setContentView(R.layout.activity_main);

        // Init layout components
        toolbar = (Toolbar)findViewById(R.id.toolbarMain);
        listView = (ListView)findViewById(R.id.listView);
        newNote = (ImageButton)findViewById(R.id.newNote);
        noNotes = (TextView)findViewById(R.id.noNotes);

        if (toolbar != null)
            initToolbar();

        newNoteButtonBaseYCoordinate = newNote.getY();

        // Initialize NoteAdapter with notes array
        adapter = new NoteAdapter(getApplicationContext(), notes);
        listView.setAdapter(adapter);

        // Set item click, multi choice and scroll listeners
        listView.setOnItemClickListener(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        listView.setMultiChoiceModeListener(this);
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // If last first visible item not initialized -> set to current first
                if (lastFirstVisibleItem == -1)
                    lastFirstVisibleItem = view.getFirstVisiblePosition();

                // If scrolled up -> hide newNote button
                if (view.getFirstVisiblePosition() > lastFirstVisibleItem)
                    newNoteButtonVisibility(false);

                // If scrolled down and delete/search not active -> show newNote button
                else if (view.getFirstVisiblePosition() < lastFirstVisibleItem &&
                        !deleteActive && !searchActive) {

                    newNoteButtonVisibility(true);
                }

                // Set last first visible item to current
                lastFirstVisibleItem = view.getFirstVisiblePosition();
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {}
        });


        // If newNote button clicked -> Start EditActivity intent with NEW_NOTE_REQUEST as request
        newNote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, EditActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                intent.putExtra(NOTE_REQUEST_CODE, NEW_NOTE_REQUEST);

                startActivityForResult(intent, NEW_NOTE_REQUEST);
            }
        });

        // If no notes -> show 'Press + to add new note' text, invisible otherwise
        if (notes.length() == 0)
            noNotes.setVisibility(View.VISIBLE);

        else
            noNotes.setVisibility(View.INVISIBLE);

        initDialogs(this);
    }

    /**
     * Initialize toolbar with required components such as
     * - title, menu/OnMenuItemClickListener and searchView -
     */
    private void initToolbar() {
        toolbar.setTitle(R.string.app_name);

        // Inflate menu_main to be displayed in the toolbar
        toolbar.inflateMenu(R.menu.menu_main);

        // Set an OnMenuItemClickListener to handle menu item clicks
        toolbar.setOnMenuItemClickListener(this);

        Menu menu = toolbar.getMenu();

        if (menu != null) {
            // Get 'Search' menu item
            searchMenu = menu.findItem(R.id.action_search);

            if (searchMenu != null) {
                // If the item menu not null -> get it's support action view
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchMenu);

                if (searchView != null) {
                    // If searchView not null -> set query hint and open/query/close listeners
                    searchView.setQueryHint(getString(R.string.action_search));
                    searchView.setOnQueryTextListener(this);

                    MenuItemCompat.setOnActionExpandListener(searchMenu,
                            new MenuItemCompat.OnActionExpandListener() {

                        @Override
                        public boolean onMenuItemActionExpand(MenuItem item) {
                            searchActive = true;
                            newNoteButtonVisibility(false);
                            // Disable long-click on listView to prevent deletion
                            listView.setLongClickable(false);

                            // Init realIndexes array
                            realIndexesOfSearchResults = new ArrayList<>();
                            for (int i = 0; i < notes.length(); i++)
                                realIndexesOfSearchResults.add(i);

                            adapter.notifyDataSetChanged();

                            return true;
                        }

                        @Override
                        public boolean onMenuItemActionCollapse(MenuItem item) {
                            searchEnded();
                            return true;
                        }
                    });
                }
            }
        }
    }

    /**
     * Implementation of AlertDialogs such as
     * - backupCheckDialog, backupOKDialog, restoreCheckDialog, restoreFailedDialog -
     * @param context The Activity context of the dialogs; in this case MainActivity context
     */
    private void initDialogs(Context context) {
        /*
         * Backup check dialog
         *  If not sure -> dismiss
         *  If yes -> check if notes length > 0
         *    If yes -> save current notes to backup file in backupPath
         */
        backupCheckDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.action_backup)
                .setMessage(R.string.dialog_check_backup_if_sure)
                .setPositiveButton(R.string.yes_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // If note array not empty -> continue
                        if (notes.length() > 0) {
                            boolean backupSuccessful = DataUtils.saveData(backupPath, notes);

                            if (backupSuccessful) {
                                showBackupSuccessfulDialog();
                            } else {
                                showToast(R.string.toast_backup_failed);
                            }
                        } else { // If notes array is empty -> toast backup no notes found
                            showToast(R.string.toast_backup_no_notes);
                        }
                    }
                })
                .setNegativeButton(R.string.no_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();

        cloudBackupCheckDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.action_cloud_backup)
                .setMessage(R.string.dialog_check_backup_if_sure)
                .setPositiveButton(R.string.yes_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // If note array not empty -> continue
                        if (notes.length() > 0) {
                            saveDataToCloud(notes, BACKUP_FILE_NAME);
                            // TODO: Check that this really happens
                        } else { // If notes array is empty -> toast backup no notes found
                            showToast(R.string.toast_backup_no_notes);
                        }
                    }
                })
                .setNegativeButton(R.string.no_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();

        // Dialog to display backup was successfully created in backupPath
        backupOKDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_backup_created_title)
                .setMessage(getString(R.string.dialog_backup_created) + " "
                        + backupPath.getAbsolutePath())
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
        // Dialog to display backup was successfully uploaded to GDrive
        cloudBackupOKDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_backup_created_title)
                .setMessage(getString(R.string.dialog_cloud_backup_created))
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();

        /*
         * Restore check dialog
         *  If not sure -> dismiss
         *  If yes -> check if backup notes exists
         *    If not -> display restore failed dialog
         *    If yes -> retrieve notes from backup file and store into local file
         */
        restoreCheckDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.action_restore)
                .setMessage(R.string.dialog_check_restore_if_sure)
                .setPositiveButton(R.string.yes_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        JSONArray tempNotes = DataUtils.retrieveData(backupPath);

                        // If backup file exists -> copy backup notes to local file
                        if (tempNotes != null) {
                            boolean restoreSuccessful = DataUtils.saveData(localPath, tempNotes);

                            if (restoreSuccessful) {
                                notes = tempNotes;
                                adapter = new NoteAdapter(getApplicationContext(), notes);
                                listView.setAdapter(adapter);

                                showToast(R.string.toast_restore_successful);

                                // If no notes -> show 'Press + to add new note' text, invisible otherwise
                                if (notes.length() == 0) {
                                    noNotes.setVisibility(View.VISIBLE);
                                } else
                                    noNotes.setVisibility(View.INVISIBLE);
                            } else {
                                showToast(R.string.toast_restore_unsuccessful);
                            }
                        }

                        // If backup file doesn't exist -> show restore failed dialog
                        else
                            showRestoreFailedDialog();
                    }
                })
                .setNegativeButton(R.string.no_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();

        cloudRestoreCheckDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.action_restore)
                .setMessage(R.string.dialog_check_restore_if_sure)
                .setPositiveButton(R.string.yes_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        getCloudBackup();
                    }
                })
                .setNegativeButton(R.string.no_button, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();


        // Dialog to display restore failed when no backup file found
        restoreFailedDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_restore_failed_title)
                .setMessage(R.string.dialog_restore_failed)
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
        // Dialog to display restore failed when no backup file found
        cloudRestoreFailedDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_restore_failed_title)
                .setMessage(R.string.dialog_cloud_restore_failed)
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
    }

    // Method to dismiss backup check and show backup successful dialog
    private void showBackupSuccessfulDialog() {
        backupCheckDialog.dismiss();
        backupOKDialog.show();
    }

    // Method to dismiss backup check and show backup successful dialog
    private void showCloudBackupSuccessfulDialog() {
        cloudBackupCheckDialog.dismiss();
        cloudBackupOKDialog.show();
    }

    // Method to dismiss restore check and show restore failed dialog
    private void showRestoreFailedDialog() {
        restoreCheckDialog.dismiss();
        restoreFailedDialog.show();
    }

    // Method to dismiss restore check and show restore failed dialog
    private void showCloudRestoreFailedDialog() {
        cloudRestoreCheckDialog.dismiss();
        cloudRestoreFailedDialog.show();
    }

    /**
     * If item clicked in list view -> Start EditActivity intent with position as requestCode
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Intent intent = new Intent(this, EditActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

        // If search is active -> use position from realIndexesOfSearchResults for EditActivity
        if (searchActive) {
            int newPosition = realIndexesOfSearchResults.get(position);

            try {
                // Package selected note content and send to EditActivity
                intent.putExtra(NOTE_TITLE, notes.getJSONObject(newPosition).getString(NOTE_TITLE));
                intent.putExtra(NOTE_BODY, notes.getJSONObject(newPosition).getString(NOTE_BODY));
                intent.putExtra(NOTE_COLOUR, notes.getJSONObject(newPosition).getString(NOTE_COLOUR));
                intent.putExtra(NOTE_FONT_SIZE, notes.getJSONObject(newPosition).getInt(NOTE_FONT_SIZE));

                if (notes.getJSONObject(newPosition).has(NOTE_HIDE_BODY)) {
                    intent.putExtra(NOTE_HIDE_BODY,
                            notes.getJSONObject(newPosition).getBoolean(NOTE_HIDE_BODY));
                } else
                    intent.putExtra(NOTE_HIDE_BODY, false);

            } catch (JSONException e) {
                e.printStackTrace();
            }

            intent.putExtra(NOTE_REQUEST_CODE, newPosition);
            startActivityForResult(intent, newPosition);
        }

        // If search is not active -> use normal position for EditActivity
        else {
            try {
                // Package selected note content and send to EditActivity
                intent.putExtra(NOTE_TITLE, notes.getJSONObject(position).getString(NOTE_TITLE));
                intent.putExtra(NOTE_BODY, notes.getJSONObject(position).getString(NOTE_BODY));
                intent.putExtra(NOTE_COLOUR, notes.getJSONObject(position).getString(NOTE_COLOUR));
                intent.putExtra(NOTE_FONT_SIZE, notes.getJSONObject(position).getInt(NOTE_FONT_SIZE));

                if (notes.getJSONObject(position).has(NOTE_HIDE_BODY)) {
                    intent.putExtra(NOTE_HIDE_BODY,
                            notes.getJSONObject(position).getBoolean(NOTE_HIDE_BODY));
                } else
                    intent.putExtra(NOTE_HIDE_BODY, false);

            } catch (JSONException e) {
                e.printStackTrace();
            }

            intent.putExtra(NOTE_REQUEST_CODE, position);
            startActivityForResult(intent, position);
        }
    }


    /**
     * Item clicked in Toolbar menu callback method
     * @param menuItem Item clicked
     * @return true if click detected and logic finished, false otherwise
     */
    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        int id = menuItem.getItemId();

        if (id == R.id.action_cloud_backup) {
            cloudBackupCheckDialog.show();
            return true;
        } else if (id == R.id.action_backup) {
            backupCheckDialog.show();
            return true;
        } else if (id == R.id.action_cloud_restore) {
            cloudRestoreCheckDialog.show();
        }
        else if (id == R.id.action_restore) {
            restoreCheckDialog.show();
            return true;
        }

        // 'Rate app' pressed -> create new dialog to ask the user if he wants to go to the PlayStore
        // If yes -> start PlayStore and go to app link < If Exception thrown, open in Browser >
        else if (id == R.id.action_rate_app) {
            final String appPackageName = getPackageName();

            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_rate_title)
                    .setMessage(R.string.dialog_rate_message)
                    .setPositiveButton(R.string.yes_button, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=" + appPackageName)));

                            } catch (android.content.ActivityNotFoundException anfe) {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("http://play.google.com/store/apps/details?id="
                                                + appPackageName)));
                            }
                        }
                    })
                    .setNegativeButton(R.string.no_button, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .show();

            return true;
        }
        return false;
    }

    /**
     * During multi-choice menu_delete selection mode, callback method if items checked changed
     * @param mode ActionMode of selection
     * @param position Position checked
     * @param id ID of item, if exists
     * @param checked true if checked, false otherwise
     */
    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        // If item checked -> add to array
        if (checked) {
            checkedArray.add(position);
        } else {
            int index = -1;

            // Loop through array and find index of item unchecked
            for (int i = 0; i < checkedArray.size(); i++) {
                if (position == checkedArray.get(i)) {
                    index = i;
                    break;
                }
            }

            // If index was found -> remove the item
            if (index != -1)
                checkedArray.remove(index);
        }

        // Set Toolbar title to 'x Selected'
        mode.setTitle(checkedArray.size() + " " + getString(R.string.action_delete_selected_number));
        adapter.notifyDataSetChanged();
    }

    /**
     * Callback method when 'Delete' icon pressed
     * @param mode ActionMode of selection
     * @param item MenuItem clicked, in our case just action_delete
     * @return true if clicked, false otherwise
     */
    @Override
    public boolean onActionItemClicked(final ActionMode mode, MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.dialog_delete)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Pass notes and checked items for deletion array to 'deleteNotes'
                            notes = DataUtils.deleteNotes(notes, checkedArray);

                            // Create and set new adapter with new notes array
                            adapter = new NoteAdapter(getApplicationContext(), notes);
                            listView.setAdapter(adapter);

                            // Attempt to save notes to local file
                            Boolean saveSuccessful = DataUtils.saveData(localPath, notes);
                            saveDataToCloud(notes, DRIVE_FILE_NAME);
                            // TODO: Check that this really happens

                            // If save successful -> toast successfully deleted
                            if (saveSuccessful) {
                                showToast(R.string.toast_deleted);
                            }

                            // Smooth scroll to top
                            listView.post(new Runnable() {
                                public void run() {
                                    listView.smoothScrollToPosition(0);
                                }
                            });

                            // If no notes -> show 'Press + to add new note' text, invisible otherwise
                            if (notes.length() == 0) {
                                noNotes.setVisibility(View.VISIBLE);
                            } else {
                                noNotes.setVisibility(View.INVISIBLE);
                            }
                            mode.finish();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .show();

            return true;
        }
        return false;
    }

    // Long click detected on ListView item -> start selection ActionMode (delete mode)
    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.menu_delete, menu); // Inflate 'menu_delete' menu
        deleteActive = true; // Set deleteActive to true as we entered delete mode
        newNoteButtonVisibility(false); // Hide newNote button
        adapter.notifyDataSetChanged(); // Notify adapter to hide favourite buttons
        return true;
    }

    // Selection ActionMode finished (delete mode ended)
    @Override
    public void onDestroyActionMode(ActionMode mode) {
        checkedArray = new ArrayList<>(); // Reset checkedArray
        deleteActive = false; // Set deleteActive to false as we finished delete mode
        newNoteButtonVisibility(true); // Show newNote button
        adapter.notifyDataSetChanged(); // Notify adapter to show favourite buttons
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }


    /**
     * Method to show and hide the newNote button
     * @param isVisible true to show button, false to hide
     */
    private void newNoteButtonVisibility(boolean isVisible) {
        if (isVisible) {
            newNote.animate().cancel();
            newNote.animate().translationY(newNoteButtonBaseYCoordinate);
        } else {
            newNote.animate().cancel();
            newNote.animate().translationY(newNoteButtonBaseYCoordinate + 500);
        }
    }


    /**
     * Callback method for 'searchView' menu item widget text change
     * @param s String which changed
     * @return true if text changed and logic finished, false otherwise
     */
    @Override
    public boolean onQueryTextChange(String s) {
        s = s.toLowerCase(); // Turn string into lowercase

        // If query text length longer than 0
        if (s.length() > 0) {
            // Create new JSONArray and reset realIndexes array
            JSONArray notesFound = new JSONArray();
            realIndexesOfSearchResults = new ArrayList<>();

            // Loop through main notes list
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = null;

                // Get note at position i
                try {
                    note = notes.getJSONObject(i);

                } catch (JSONException e) {
                    e.printStackTrace();
                }

                // If note not null and title/body contain query text
                // -> Put in new notes array and add i to realIndexes array
                if (note != null) {
                    try {
                        if (note.getString(NOTE_TITLE).toLowerCase().contains(s) ||
                            note.getString(NOTE_BODY).toLowerCase().contains(s)) {

                            notesFound.put(note);
                            realIndexesOfSearchResults.add(i);
                        }

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Create and set adapter with notesFound to refresh ListView
            NoteAdapter searchAdapter = new NoteAdapter(getApplicationContext(), notesFound);
            listView.setAdapter(searchAdapter);
        }

        // If query text length is 0 -> re-init realIndexes array (0 to length) and reset adapter
        else {
            realIndexesOfSearchResults = new ArrayList<>();
            for (int i = 0; i < notes.length(); i++)
                realIndexesOfSearchResults.add(i);

            adapter = new NoteAdapter(getApplicationContext(), notes);
            listView.setAdapter(adapter);
        }

        return false;
    }

    @Override
    public boolean onQueryTextSubmit(String s) {
        return false;
    }

    /**
     * When search mode is finished
     * Collapse searchView widget, searchActive to false, reset adapter, enable listView long clicks
     * and show newNote button
     */
    private void searchEnded() {
        searchActive = false;
        adapter = new NoteAdapter(getApplicationContext(), notes);
        listView.setAdapter(adapter);
        listView.setLongClickable(true);
        newNoteButtonVisibility(true);
    }

    /**
     * Callback method when EditActivity finished adding new note or editing existing note
     * @param requestCode requestCode for intent sent, in our case either NEW_NOTE_REQUEST or position
     * @param resultCode resultCode from activity, either RESULT_OK or RESULT_CANCELED
     * @param data Data bundle passed back from EditActivity
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            // If search was active -> call 'searchEnded' method
            if (searchActive && searchMenu != null)
                searchMenu.collapseActionView();

            // Get extras
            Bundle mBundle = null;
            if (data != null)
                mBundle = data.getExtras();

            if (mBundle != null) {
                // If new note was saved
                if (requestCode == NEW_NOTE_REQUEST) {
                    JSONObject newNoteObject;

                    try {
                        // Add new note to array
                        newNoteObject = new JSONObject();
                        newNoteObject.put(NOTE_TITLE, mBundle.getString(NOTE_TITLE));
                        newNoteObject.put(NOTE_BODY, mBundle.getString(NOTE_BODY));
                        newNoteObject.put(NOTE_COLOUR, mBundle.getString(NOTE_COLOUR));
                        newNoteObject.put(NOTE_FAVOURED, false);
                        newNoteObject.put(NOTE_FONT_SIZE, mBundle.getInt(NOTE_FONT_SIZE));
                        newNoteObject.put(NOTE_HIDE_BODY, mBundle.getBoolean(NOTE_HIDE_BODY));

                        notes.put(newNoteObject);

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    // If newNoteObject not null -> save notes array to local file and notify adapter
                    adapter.notifyDataSetChanged();

                    Boolean saveSuccessful = DataUtils.saveData(localPath, notes);
                    saveDataToCloud(notes, DRIVE_FILE_NAME);
                    // TODO: Check that this really happens

                    if (saveSuccessful) {
                        showToast(R.string.toast_new_note);
                    }

                    // If no notes -> show 'Press + to add new note' text, invisible otherwise
                    if (notes.length() == 0) {
                        noNotes.setVisibility(View.VISIBLE);
                    }

                    else {
                        noNotes.setVisibility(View.INVISIBLE);
                    }
                } else { // If existing note was updated (saved)
                    JSONObject newNoteObject = null;

                    try {
                        // Update array item with new note data
                        newNoteObject = notes.getJSONObject(requestCode);
                        newNoteObject.put(NOTE_TITLE, mBundle.getString(NOTE_TITLE));
                        newNoteObject.put(NOTE_BODY, mBundle.getString(NOTE_BODY));
                        newNoteObject.put(NOTE_COLOUR, mBundle.getString(NOTE_COLOUR));
                        newNoteObject.put(NOTE_FONT_SIZE, mBundle.getInt(NOTE_FONT_SIZE));
                        newNoteObject.put(NOTE_HIDE_BODY, mBundle.getBoolean(NOTE_HIDE_BODY));

                        // Update note at position 'requestCode'
                        notes.put(requestCode, newNoteObject);

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    // If newNoteObject not null -> save notes array to local file and notify adapter
                    if (newNoteObject != null) {
                        adapter.notifyDataSetChanged();

                        Boolean saveSuccessful = DataUtils.saveData(localPath, notes);
                        saveDataToCloud(notes, DRIVE_FILE_NAME);
                        // TODO: Check that this really happens

                        if (saveSuccessful) {
                            showToast(R.string.toast_note_saved);
                        }
                    }
                }
            }
        } else if (resultCode == RESULT_CANCELED) {
            Bundle mBundle;

            // If data is not null, has "request" extra and is new note -> get extras to bundle
            if (data != null && data.hasExtra("request") && requestCode == NEW_NOTE_REQUEST) {
                mBundle = data.getExtras();

                // If new note discarded -> toast empty note discarded
                if (mBundle != null && "discard".equals(mBundle.getString("request"))) {
                    showToast(R.string.toast_empty_note_discarded);
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }


    /**
     * Favourite or un-favourite the note at position
     * @param context application context
     * @param favourite true to favourite, false to un-favourite
     * @param position position of note
     */
    public static void setFavourite(Context context, boolean favourite, int position) {
        JSONObject newFavourite = null;

        // Get note at position and store in newFavourite
        try {
            newFavourite = notes.getJSONObject(position);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (newFavourite != null) {
            if (favourite) {
                // Set favoured to true
                try {
                    newFavourite.put(NOTE_FAVOURED, true);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                // If favoured note is not at position 0
                // Sort notes array so favoured note is first
                if (position > 0) {
                    JSONArray newArray = new JSONArray();

                    try {
                        newArray.put(0, newFavourite);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    // Copy contents to new sorted array without favoured element
                    for (int i = 0; i < notes.length(); i++) {
                        if (i != position) {
                            try {
                                newArray.put(notes.get(i));

                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    // Equal main notes array with new sorted array and reset adapter
                    notes = newArray;
                    adapter = new NoteAdapter(context, notes);
                    listView.setAdapter(adapter);

                    // Smooth scroll to top
                    listView.post(new Runnable() {
                        public void run() {
                            listView.smoothScrollToPosition(0);
                        }
                    });
                }

                // If favoured note was first -> just update object in notes array and notify adapter
                else {
                    try {
                        notes.put(position, newFavourite);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    adapter.notifyDataSetChanged();
                }
            }

            // If note not favourite -> set favoured to false and notify adapter
            else {
                try {
                    newFavourite.put(NOTE_FAVOURED, false);
                    notes.put(position, newFavourite);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                adapter.notifyDataSetChanged();
            }

            // Save notes to local file
            DataUtils.saveData(localPath, notes);
            // Cant save from static context
        }
    }

    /*
        Query GDrive for first file named swiftnotes_data.json.
        Calls restoreBackup() on reply.
        Clusterfuck of nested callbacks.
     */
    private void getCloudBackup() {
        Query query = new Query.Builder()
                .addFilter(Filters.eq(SearchableField.TITLE, BACKUP_FILE_NAME))
                .build();
        Log.i(TAG, "Lets get cloud backup from Google Drive.");
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
                        driveFile.open(mGoogleApiClient, DriveFile.MODE_READ_ONLY, null)
                                .setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {
                            @Override
                            public void onResult(@NonNull DriveApi.DriveContentsResult result) {
                                if (!result.getStatus().isSuccess()) {
                                    Log.e(TAG, "Could not open files on Google Drive.");
                                    return;
                                }
                                Log.i(TAG, "Retrieved backup.");
                                InputStreamReader isr = new InputStreamReader(
                                        result.getDriveContents().getInputStream());
                                restoreCloudData(retrieveData(isr));
                            }
                          });
                    }
                });
    }

    /*
        Tries to save received data from cloud into localPath.
     */
    private void restoreCloudData(JSONArray tempNotes) {
        boolean restoreSuccessful = DataUtils.saveData(localPath, tempNotes);
        if (restoreSuccessful) {
            Log.i(TAG, "restore successful");
            notes = tempNotes;

            adapter = new NoteAdapter(getApplicationContext(), notes);
            listView.setAdapter(adapter);

            // TODO: Not so sure this is really successful
            showToast(R.string.toast_restore_successful);

            // If no notes -> show 'Press + to add new note' text, invisible otherwise
            if (notes.length() == 0) {
                noNotes.setVisibility(View.VISIBLE);
            } else {
                noNotes.setVisibility(View.INVISIBLE);
            }
        } else {
            Log.i(TAG, "restore unsuccessful");
            showCloudRestoreFailedDialog();
        }
    }

    /**
     * Wrap 'notes' array into a root object and store on Google Drive
     * @param notes Array of notes to be saved
     */
    private void saveDataToCloud(JSONArray notes, final String fileName) {
        final JSONObject root = new JSONObject();

        // If passed notes not null -> wrap in root JSONObject
        if (notes != null) {
            try {
                root.put(NOTES_ARRAY_NAME, notes);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
        } else {
            return;
        }

        Drive.DriveApi.newDriveContents(mGoogleApiClient)
                .setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {
                    @Override
                    public void onResult(@NonNull DriveApi.DriveContentsResult result) {
                        if (!result.getStatus().isSuccess()) {
                            Log.i(TAG, "Failed to create new contents.");
                            return;
                        }
                        Log.i(TAG, "New contents created.");
                        final DriveContents driveContents = result.getDriveContents();
                        uploadFile(driveContents, root.toString(), fileName,
                                    "application/json");
                    }
                });
    }


    /*
        Upload file to Google Drive with contents in str, title as title, mimetype as mimetype.
     */
    private void uploadFile(final DriveContents driveContents,
                            final String str, final String title, final String mimetype) {
        OutputStream outputStream = driveContents.getOutputStream();
        Writer writer = new OutputStreamWriter(outputStream);
        try {
            writer.write(str);
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(title)
                .setMimeType(mimetype)
                .setStarred(false).build();

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
                        showCloudBackupSuccessfulDialog();
                        trashOldFiles(currentDriveId, title);
                    }
                });
    }

    /*
    Trash old files matching filename, except given DriveId.
    */
    private void trashOldFiles(final DriveId currentDriveId, final String fileName) {
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
     * Shows a toast with the given message.
     */
    private void showToast(int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

    /**
     * If back button pressed while search is active -> collapse view and end search mode
     */
    @Override
    public void onBackPressed() {
        if (searchActive && searchMenu != null) {
            searchMenu.collapseActionView();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Orientation changed callback method
     * If orientation changed -> If any AlertDialog is showing, dismiss it to prevent WindowLeaks
     * @param newConfig New Configuration passed by system
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (backupCheckDialog != null && backupCheckDialog.isShowing())
            backupCheckDialog.dismiss();

        if (cloudBackupCheckDialog != null && cloudBackupCheckDialog.isShowing())
            cloudBackupCheckDialog.dismiss();

        if (backupOKDialog != null && backupOKDialog.isShowing())
            backupOKDialog.dismiss();

        if (cloudBackupOKDialog != null && cloudBackupOKDialog.isShowing())
            cloudBackupOKDialog.dismiss();

        if (cloudRestoreCheckDialog != null && cloudRestoreCheckDialog.isShowing())
            restoreCheckDialog.dismiss();

        if (restoreCheckDialog != null && restoreCheckDialog.isShowing())
            restoreCheckDialog.dismiss();

        if (restoreFailedDialog != null && restoreFailedDialog.isShowing())
            restoreFailedDialog.dismiss();

        if (cloudRestoreFailedDialog != null && cloudRestoreFailedDialog.isShowing())
            restoreFailedDialog.dismiss();

        super.onConfigurationChanged(newConfig);
    }

    // Static method to return File at localPath
    public static File getLocalPath() {
        return localPath;
    }

    // Static method to return File at backupPath
    public static File getBackupPath() {
        return backupPath;
    }

    @Override
    public void onResume() {
        super.onResume();
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
    }

    @Override
    protected void onPause() {
        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }
        super.onPause();
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
            GoogleApiAvailability.getInstance().getErrorDialog(this, result.getErrorCode(), 0).show();
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

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.i(TAG, "API client connected.");
    }

}
