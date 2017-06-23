/*
 * Copyright 2009-2016 Brian Pellin.
 *     
 * This file is part of KeePassDroid.
 *
 *  KeePassDroid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDroid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepassdroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import com.android.keepass.KeePass;
import com.android.keepass.R;
import com.keepassdroid.app.App;
import com.keepassdroid.compat.BackupManagerCompat;
import com.keepassdroid.compat.ClipDataCompat;
import com.keepassdroid.compat.EditorCompat;
import com.keepassdroid.compat.StorageAF;
import com.keepassdroid.database.edit.LoadDB;
import com.keepassdroid.database.edit.OnFinish;
import com.keepassdroid.dialog.PasswordEncodingDialogHelper;
import com.keepassdroid.fileselect.BrowserDialog;
import com.keepassdroid.intents.Intents;
import com.keepassdroid.mycode.apduCodes;
import com.keepassdroid.mycode.backgroundService;
import com.keepassdroid.settings.AppSettingsActivity;
import com.keepassdroid.utils.EmptyUtils;
import com.keepassdroid.utils.Interaction;
import com.keepassdroid.utils.UriUtil;
import com.keepassdroid.utils.Util;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class PasswordActivity extends LockingActivity {

    public static final String KEY_DEFAULT_FILENAME = "defaultFileName";
    private static final String KEY_FILENAME = "fileName";
    private static final String KEY_KEYFILE = "keyFile";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_LAUNCH_IMMEDIATELY = "launchImmediately";
    private static final String VIEW_INTENT = "android.intent.action.VIEW";

    private static final int FILE_BROWSE = 256;
    public static final int GET_CONTENT = 257;
    private static final int OPEN_DOC = 258;

    private Uri mDbUri = null;
    private Uri mKeyUri = null;
    private boolean mRememberKeyfile;
    SharedPreferences prefs;

    public static void Launch(Activity act, String fileName) throws FileNotFoundException {
        Launch(act,fileName,"");
    }

    public static void Launch(Activity act, String fileName, String keyFile) throws FileNotFoundException {
        if (EmptyUtils.isNullOrEmpty(fileName)) {
            throw new FileNotFoundException();
        }

        Uri uri = UriUtil.parseDefaultFile(fileName);
        String scheme = uri.getScheme();

        if (!EmptyUtils.isNullOrEmpty(scheme) && scheme.equalsIgnoreCase("file")) {
            File dbFile = new File(uri.getPath());
            if (!dbFile.exists()) {
                throw new FileNotFoundException();
            }
        }

        Intent i = new Intent(act, PasswordActivity.class);
        i.putExtra(KEY_FILENAME, fileName);
        i.putExtra(KEY_KEYFILE, keyFile);
        act.startActivityForResult(i, 0);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(!isMyServiceRunning(backgroundService.class.getName())){
            Intent intent1 = new Intent(this, backgroundService.class);
            stopService(intent1);
        }

        switch (requestCode) {

        case KeePass.EXIT_NORMAL:
            setEditText(R.id.password, "");
            App.getDB().clear();
            break;

        case KeePass.EXIT_LOCK:
            setResult(KeePass.EXIT_LOCK);
            setEditText(R.id.password, "");
            finish();
            App.getDB().clear();
            break;
        case FILE_BROWSE:
            if (resultCode == RESULT_OK) {
                String filename = data.getDataString();
                if (filename != null) {
                    EditText fn = (EditText) findViewById(R.id.pass_keyfile);
                    fn.setText(filename);
                    mKeyUri = UriUtil.parseDefaultFile(filename);
                }
            }
            break;
        case GET_CONTENT:
        case OPEN_DOC:
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        if (requestCode==GET_CONTENT) {
                            uri = UriUtil.translate(this, uri);
                        }
                        String path = uri.toString();
                        if (path != null) {
                            EditText fn = (EditText) findViewById(R.id.pass_keyfile);
                            fn.setText(path);

                        }
                        mKeyUri = uri;
                    }
                }
            }
            break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = getIntent();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mRememberKeyfile = prefs.getBoolean(getString(R.string.keyfile_key), getResources().getBoolean(R.bool.keyfile_default));
        setContentView(R.layout.password);


        new InitTask().execute(i);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If the application was shutdown make sure to clear the password field, if it
        // was saved in the instance state
        if (App.isShutdown()) {
            TextView password = (TextView) findViewById(R.id.password);
            password.setText("");
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);

        try {
            ndef.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("fail", e);
        }

        mFilters = new IntentFilter[] { ndef, };
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }

        // Clear the shutdown flag
        App.clearShutdown();
    }

    private void retrieveSettings() {
        String defaultFilename = prefs.getString(KEY_DEFAULT_FILENAME, "");
        if (!EmptyUtils.isNullOrEmpty(mDbUri.getPath()) && UriUtil.equalsDefaultfile(mDbUri, defaultFilename)) {
            CheckBox checkbox = (CheckBox) findViewById(R.id.default_database);
            checkbox.setChecked(true);
        }
    }

    private Uri getKeyFile(Uri dbUri) {
        if ( mRememberKeyfile ) {
            return App.getFileHistory().getFileByName(dbUri);
        } else {
            return null;
        }
    }

    private void populateView() {
        String db = (mDbUri == null) ? "" : mDbUri.toString();
        setEditText(R.id.filename, db);

        String key = (mKeyUri == null) ? "" : mKeyUri.toString();
        setEditText(R.id.pass_keyfile, key);
    }

    /*
    private void errorMessage(CharSequence text)
    {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
    */

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }


    private void errorMessage(int resId)
    {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private class DefaultCheckChange implements CompoundButton.OnCheckedChangeListener {

        @Override
        public void onCheckedChanged(CompoundButton buttonView,
                boolean isChecked) {

            String newDefaultFileName;

            if (isChecked) {
                newDefaultFileName = mDbUri.toString();
            } else {
                newDefaultFileName = "";
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_DEFAULT_FILENAME, newDefaultFileName);
            EditorCompat.apply(editor);

            BackupManagerCompat backupManager = new BackupManagerCompat(PasswordActivity.this);
            backupManager.dataChanged();

        }

    }

    private class OkClickHandler implements View.OnClickListener {

        public void onClick(View view) {
            String pass = getEditText(R.id.password);
            String key = getEditText(R.id.pass_keyfile);
            loadDatabase(pass, key);
        }
    }

    private void loadDatabase(String pass, String keyfile) {
        loadDatabase(pass, UriUtil.parseDefaultFile(keyfile));
    }

    private void loadDatabase(String pass, Uri keyfile)
    {
        if ( pass.length() == 0 && (keyfile == null || keyfile.toString().length() == 0)) {
            errorMessage(R.string.error_nopass);
            return;
        }

        // Clear before we load
        Database db = App.getDB();
        db.clear();

        // Clear the shutdown flag
        App.clearShutdown();

        Handler handler = new Handler();
        LoadDB task = new LoadDB(db, PasswordActivity.this, mDbUri, pass, keyfile, new AfterLoad(handler, db));
        ProgressTask pt = new ProgressTask(PasswordActivity.this, task, R.string.loading_database);
        pt.run();
    }

    private String getEditText(int resId) {
        return Util.getEditText(this, resId);
    }

    private void setEditText(int resId, String str) {
        TextView te =  (TextView) findViewById(resId);
        assert(te == null);

        if (te != null) {
            te.setText(str);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflate = getMenuInflater();
        inflate.inflate(R.menu.password, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch ( item.getItemId() ) {
        case R.id.menu_about:
            AboutDialog dialog = new AboutDialog(this);
            dialog.show();
            return true;

        case R.id.menu_app_settings:
            AppSettingsActivity.Launch(this);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private final class AfterLoad extends OnFinish {
        private Database db;

        public AfterLoad(Handler handler, Database db) {
            super(handler);

            this.db = db;
        }

        @Override
        public void run() {
            if ( db.passwordEncodingError) {
                PasswordEncodingDialogHelper dialog = new PasswordEncodingDialogHelper();
                dialog.show(PasswordActivity.this, new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        GroupActivity.Launch(PasswordActivity.this);
                    }

                });
            } else if ( mSuccess ) {
                GroupActivity.Launch(PasswordActivity.this);
            } else {
                displayMessage(PasswordActivity.this);
            }
        }
    }

    private class InitTask extends AsyncTask<Intent, Void, Integer> {
        String password = "";
        boolean launch_immediately = false;

        @Override
        protected Integer doInBackground(Intent... args) {
            Intent i = args[0];
            String action = i.getAction();
            if ( action != null && action.equals(VIEW_INTENT) ) {
                Uri incoming = i.getData();
                mDbUri = incoming;

				mKeyUri = ClipDataCompat.getUriFromIntent(i, KEY_KEYFILE);

                if (incoming == null) {
                    return R.string.error_can_not_handle_uri;
                }
                else if (incoming.getScheme().equals("file")) {
                    String fileName = incoming.getPath();

                    if (fileName.length() == 0) {
                        // No file name
                        return R.string.FileNotFound;
                    }

                    File dbFile = new File(fileName);
                    if (!dbFile.exists()) {
                        // File does not exist
                        return R.string.FileNotFound;
                    }

					if(mKeyUri == null)
						mKeyUri = getKeyFile(mDbUri);
                }
                else if (incoming.getScheme().equals("content")) {
					if(mKeyUri == null)
						mKeyUri = getKeyFile(mDbUri);
                }
                else {
                    return R.string.error_can_not_handle_uri;
                }
                password = i.getStringExtra(KEY_PASSWORD);
                launch_immediately = i.getBooleanExtra(KEY_LAUNCH_IMMEDIATELY, false);

            } else {
                mDbUri = UriUtil.parseDefaultFile(i.getStringExtra(KEY_FILENAME));
                mKeyUri = UriUtil.parseDefaultFile(i.getStringExtra(KEY_KEYFILE));
                password = i.getStringExtra(KEY_PASSWORD);
                launch_immediately = i.getBooleanExtra(KEY_LAUNCH_IMMEDIATELY, false);

                if ( mKeyUri == null || mKeyUri.toString().length() == 0) {
                    mKeyUri = getKeyFile(mDbUri);
                }
            }
            return null;
        }

        public void onPostExecute(Integer result) {
            if(result != null) {
                Toast.makeText(PasswordActivity.this, result, Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            populateView();

            Button confirmButton = (Button) findViewById(R.id.pass_ok);
            confirmButton.setOnClickListener(new OkClickHandler());

            CheckBox checkBox = (CheckBox) findViewById(R.id.show_password);
            // Show or hide password
            checkBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {

                public void onCheckedChanged(CompoundButton buttonView,
                        boolean isChecked) {
                    TextView password = (TextView) findViewById(R.id.password);

                    if ( isChecked ) {
                        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    } else {
                        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    }
                }

            });

            if (password != null) {
                TextView tv_password = (TextView) findViewById(R.id.password);
                tv_password.setText(password);
            }

            CheckBox defaultCheck = (CheckBox) findViewById(R.id.default_database);
            defaultCheck.setOnCheckedChangeListener(new DefaultCheckChange());

            ImageButton browse = (ImageButton) findViewById(R.id.browse_button);
            browse.setOnClickListener(new View.OnClickListener() {

                public void onClick(View v) {
                    if (StorageAF.useStorageFramework(PasswordActivity.this)) {
                        Intent i = new Intent(StorageAF.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, OPEN_DOC);
                    }
                    else {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");

                        try {
                            startActivityForResult(i, GET_CONTENT);
                        } catch (ActivityNotFoundException e) {
                            lookForOpenIntentsFilePicker();
                        }
                    }
                }

                private void lookForOpenIntentsFilePicker() {
                    if (Interaction.isIntentAvailable(PasswordActivity.this, Intents.OPEN_INTENTS_FILE_BROWSE)) {
                        Intent i = new Intent(Intents.OPEN_INTENTS_FILE_BROWSE);

                        // Get file path parent if possible
                        try {
                            if (mDbUri != null && mDbUri.toString().length() > 0) {
                                if (mDbUri.getScheme().equals("file")) {
                                    File keyfile = new File(mDbUri.getPath());
                                    File parent = keyfile.getParentFile();
                                    if (parent != null) {
                                        i.setData(Uri.parse("file://" + parent.getAbsolutePath()));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore
                        }

                        try {
                            startActivityForResult(i, FILE_BROWSE);
                        } catch (ActivityNotFoundException e) {
                            showBrowserDialog();
                        }
                    } else {
                        showBrowserDialog();
                    }
                }

                private void showBrowserDialog() {
                    BrowserDialog diag = new BrowserDialog(PasswordActivity.this);
                    diag.show();
                }
            });

            retrieveSettings();

            if (launch_immediately)
                loadDatabase(password, mKeyUri);
        }
    }

    //Code von Pascal
    private boolean isFirstConnect = true;
    private NfcAdapter nfcAdapter;
    private PendingIntent mPendingIntent;
    public static IsoDep myNFCTag;
    private String[][] mTechLists;
    private IntentFilter[] mFilters;

    private static final String logNameSend = "APDU-Commands >> ";
    private static final String logNameRecive = "APDU-Commands << ";
    private static final String logName = "APDU-Command: ";

    private byte state;
    private byte stateMPW;

    private boolean isMyServiceRunning(String className)
    {
        ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        for ( ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE) ) {
            if ( className.equals(service.service.getClassName()) ) {
                return true;
            }
        }
        return false;
    }


    public static byte[] sendandReciveData(byte[] dataToSend)
    {
        byte[] resp = null;

        try {
            Log.v(logNameSend, apduCodes.byteToHexString(dataToSend));
            resp = myNFCTag.transceive(dataToSend);
            Log.v(logNameRecive, apduCodes.byteToHexString(resp));
        } catch (IOException e) {
            Log.v(logName, "No Card Response");
        }

        return resp;
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        setIntent(intent);
        resolveIntent(intent);
    }

    private void resolveIntent(Intent intent)
    {
        String action = intent.getAction();

        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Parcelable tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            final Tag t = (Tag) tag;
            myNFCTag = IsoDep.get(t);

            if( !myNFCTag.isConnected() ) {
                try {
                    myNFCTag.connect();
                    myNFCTag.setTimeout(5000);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            if( myNFCTag.isConnected() && isFirstConnect) {
                Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                vib.vibrate(500);

                connectToCard();
            }
        }
    }

    private void connectToCard()
    {
        byte[] resp = sendandReciveData(apduCodes.apduSelectApplet);

        if ( resp != null ) {

            if (apduCodes.getResponseCode(resp).equals("9000")) {
                isFirstConnect = false;
                state = resp[0];
                Toast.makeText(getApplicationContext(), "Connected with Smartcard!", Toast.LENGTH_LONG).show();

                if (state == (byte) 0x01 || state == (byte) 0x02) {
                    showDialogVerify();
                }
            }
        }
    }

    private void showDialogVerify()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Login");
        builder.setMessage("Verify your PIN:");

        final EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input1.setHint("PIN");
        builder.setView(input1);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                String pw = apduCodes.checkLength(input1.getText().toString());
                String pwl = apduCodes.checkLength(String.valueOf(pw.length() / 2));

                if (Integer.valueOf(pwl) >= 2 && Integer.valueOf(pwl) <= 16) {
                    String data = "80210100" + pwl + pw;
                    byte[] resp = sendandReciveData(apduCodes.hexToByteArray(data));

                    if (apduCodes.getResponseStatus(resp)) {
                        stateMPW = resp[0];

                        if (stateMPW == 1) {
                            String send = "80310202";
                            byte[] response = sendandReciveData(apduCodes.hexToByteArray(send));

                            if (!myNFCTag.isConnected()) {
                                Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                            } else if (apduCodes.getResponseStatus(response)) {
                                String masterPW = apduCodes.getResponseData(response);
                                String encodedPW = apduCodes.dataHexToString(masterPW);

                                loadDatabase(encodedPW, "");
                            }
                        }

                    } else {

                        if (resp[0] == (byte)0x63 && (resp[1] & (byte)0xF0) == (byte)0xC0) {

                            if ((resp[1] & (byte)0x0F) == (byte)0x02) {
                                Toast.makeText(getApplicationContext(), "Wrong PIN: 2 trys left", Toast.LENGTH_LONG).show();
                                showDialogVerify();
                            } else if ((resp[1] & (byte)0x0F) == (byte)0x01) {
                                Toast.makeText(getApplicationContext(), "Wrong PIN: 1 trys left", Toast.LENGTH_LONG).show();
                                showDialogVerify();
                            } else if(((resp[1] & (byte)0x0F) == (byte)0x00)){
                                showDialogPINblocked();
                            }

                        } else {

                            Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                        }

                    }

                } else {
                    Toast.makeText(getApplicationContext(), "Wrong Input! Please try again", Toast.LENGTH_LONG).show();
                    showDialogVerify();
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });

        builder.show();
    }

    private void showDialogPINblocked()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Verify");
        builder.setMessage("Your PIN is blocked!\nPlease Reset Pin in Settings");

        builder.show();
    }
}
