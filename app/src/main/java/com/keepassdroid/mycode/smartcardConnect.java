package com.keepassdroid.mycode;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import com.android.keepass.R;
import com.keepassdroid.PasswordActivity;
import com.keepassdroid.app.App;
import com.keepassdroid.fileselect.RecentFileHistory;
import com.keepassdroid.utils.UriUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;


/**
 * Created by Pascal Hildebrand on 29.05.2017.
 *  Class for Saving KeePass Data on Smartcard
 *
 */


public class smartcardConnect extends Activity
{
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
    private String tagID;
    private String cardFileName = "";
    private AlertDialog dialog1;
    private AlertDialog dialog2;

    private boolean isPINblocked = false;
    private String inputPUK;
    private int statefile = 0;

    private RecentFileHistory fileHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        /**
         * Create NFC Adapter and add Intent Filter
         */
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        fileHistory = App.getFileHistory();

        mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);

        try {
            ndef.addDataType("*/*");
            mFilters = new IntentFilter[] { ndef, };
            mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("fail", e);
        }

        isFirstConnect = true;
        showDialogConnect();
    }

    /**
     * When Class resumed restart Intent filter
     */
    @Override
    protected void onResume()
    {
        super.onResume();

        if ( nfcAdapter != null ) {
            nfcAdapter.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
    }

    /**
     * When Class is paused stop Intent Filter
     */
    @Override
    protected void onPause() {
        super.onPause();
        if ( nfcAdapter != null ) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    /**
     * Method for Sending Data to Smartcard
     * If Error occurred Print Error in Log
     * Send and Response Log disabled so no one can read PIN or PUK from Log
     * @param dataToSend Data to Send
     * @return Response from Smartcard
     */
    public static byte[] sendandReciveData(byte[] dataToSend)
    {
        byte[] resp = null;

        try {
            //Log.v(logNameSend, apduCodes.byteToHexString(dataToSend));
            resp = myNFCTag.transceive(dataToSend);
        } catch (IOException e) {
            Log.v(logName, "No Card Response");
        }

        if( !apduCodes.getResponseStatus(resp) ) {
            Log.v(logName, "Error Code: " + apduCodes.getResponseCode(resp) + ":" + apduResponseCodes.getResponseString(apduCodes.getResponseCode(resp)));
        } else {
            //Log.v(logNameRecive, apduCodes.byteToHexString(resp));
        }

        return resp;
    }

    /**
     * Method called when Intent occurrs from NFC Interface
     * calls resolveIntent Method
     * @param intent
     */
    @Override
    protected void onNewIntent(Intent intent)
    {
        setIntent(intent);
        resolveIntent(intent);
    }

    /**
     * Method handles incoming Intent, Checks if can Connect to Smartcard or not
     * If connection successfull it starts Connect with Applet
     * @param intent
     */
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
                tagID = apduCodes.byteToHexString(t.getId());

                connectToCard();
            }

            if (myNFCTag.isConnected() && !isFirstConnect) {
                String id =  apduCodes.byteToHexString(t.getId());

                if (!id.equals(tagID)) {
                    Toast.makeText(getApplicationContext(), "Wrong Card", Toast.LENGTH_LONG).show();
                    state = (byte)0xFF;
                    stateMPW = (byte)0xFF;
                    inputPUK = "";

                    closeActivity();
                }
            }
        }
    }

    /**
     * Method which checks if Background Service is running
     * @param className
     * @return
     */
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

    /**
     * Method for Connecting App with Applet, tries to select Applet
     * and checks the state of the Applet
     */
    private void connectToCard()
    {
        byte[] resp = sendandReciveData(apduCodes.apduSelectApplet);

        if ( resp != null ) {

            if ( apduCodes.getResponseCode(resp).equals("9000") ) {
                isFirstConnect = false;
                state = resp[0];
                Toast.makeText(getApplicationContext(), "Connected with Smartcard!", Toast.LENGTH_LONG).show();
                dialog1.cancel();

                if ( state == (byte)0x00 ) {
                    personalizeSmartcard1();
                } else if ( state == (byte)0x03 ) {
                    showDialogPINblocked();
                } else {
                    showDialogVerify();
                }

            } else if ( apduCodes.getResponseCode(resp).equals("6250") ) {
                dialog1.dismiss();
                dialog1 = new AlertDialog.Builder(this).create();
                dialog1.setTitle("Smartcard Support");
                dialog1.setMessage("Smartcard status:\n--LOCKED--");

                dialog1.show();
            } else {
                Toast.makeText(getApplicationContext(), "Smartcard can´t connect!", Toast.LENGTH_LONG).show();
                closeActivity();
            }

        } else {
            Toast.makeText(getApplicationContext(), "Smartcard not supported!", Toast.LENGTH_LONG).show();
            closeActivity();
        }
    }

    /**
     * Show Dialog that App is waiting for Card
     */
    private void showDialogConnect()
    {
        dialog1 = new AlertDialog.Builder(this).create();
        dialog1.setTitle("Smartcard Support");
        dialog1.setMessage("Please keep your Card in the near!");

        dialog1.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        dialog1.show();
    }

    private void showDialogUpload()
    {
        dialog1 = new AlertDialog.Builder(this).create();
        dialog1.setTitle("Smartcard Export");
        dialog1.setMessage("Uploading File....\nPlease wait.");

        dialog1.show();
    }

    private void showDialogDownload()
    {
        dialog1 = new AlertDialog.Builder(this).create();
        dialog1.setTitle("Smartcard Import");
        dialog1.setMessage("Downloading File....\nPlease wait.");

        dialog1.show();
    }

    private void showDialogImEx()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Support");

        if (state == (byte)0x01) {
            builder.setMessage("Do you want to Export\nyour KeePass File?");
            builder.setNegativeButton("Export", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    saveDataToSmartCard();
                }
            });
        }

        if (state == (byte)0x02) {
            builder.setMessage("Do you want to Import\nor Export KeePass File?");
            builder.setPositiveButton("Import", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    loadDataFromSmartCard();
                }
            });

            builder.setNegativeButton("Export", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    saveDataToSmartCard();
                }
            });
        }

        builder.setNeutralButton("Settings", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                    showDialogSettings();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });
        builder.show();
    }

    private void showDialogSettings()
    {
        String [] choice = {"Change PIN", "Set Master Password", "Delete Master Password", "Delete All Data", "Reset Card"};


        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings").setItems(choice, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {


                        switch (which) {
                            case 0:
                                changePIN();
                                break;
                            case 1:
                                showDialogMasterPW();
                                break;
                            case 2:
                                deleteMasterPassword();
                                break;
                            case 3:
                                deleteAllData();
                                break;
                            case 4:
                                resetSmartcard();
                                break;
                        }
                    }
                });

        builder.setPositiveButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                builder.create().dismiss();
                showDialogImEx();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.create().show();
    }

    private void resetSmartcard()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Settings");
        builder.setMessage("Reset Smartcard?\nVerify your PUK:");

        final EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input1.setHint("Enter PUK");
        builder.setView(input1);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                String inPUK = input1.getText().toString();
                inPUK = inPUK.replaceAll(" ","").toLowerCase();

                if((inPUK.length() % 2) != 0 || inPUK.length() != 16 || !apduCodes.checkInput(inPUK)) {
                    Toast.makeText(getApplicationContext(), "Error! Wrong Input", Toast.LENGTH_LONG).show();
                    resetSmartcard();
                } else {
                    String data = "8024000008" + inPUK;
                    byte[] resp = sendandReciveData(apduCodes.hexToByteArray(data));

                    if(apduCodes.getResponseStatus(resp)) {
                        Toast.makeText(getApplicationContext(), "Smartcard Reset successful", Toast.LENGTH_LONG).show();
                        closeActivity();
                    } else {
                        Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                        resetSmartcard();
                    }
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDialogSettings();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void deleteAllData()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Settings");
        builder.setMessage("Delete all Data on Smartcard?\nVerify your PIN:");

        final EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input1.setHint("Enter PIN");
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
                        String data1 = "80430303";
                        byte[] resp1 = sendandReciveData(apduCodes.hexToByteArray(data1));

                        if (apduCodes.getResponseStatus(resp1)) {
                            Toast.makeText(getApplicationContext(), "Data delete successful!", Toast.LENGTH_LONG).show();
                            state = (byte)0x01;
                            stateMPW = (byte)0x02;
                            showDialogSettings();
                        }

                        Toast.makeText(getApplicationContext(), "Data delete Failed!", Toast.LENGTH_LONG).show();
                        showDialogSettings();
                    } else {

                        if (resp[0] == (byte)0x63 && (resp[1] & (byte)0xF0) == (byte)0xC0) {

                            if ((resp[1] & (byte)0x0F) == (byte)0x02) {
                                Toast.makeText(getApplicationContext(), "Wrong PIN: 2 trys left", Toast.LENGTH_LONG).show();
                                deleteAllData();
                            } else if ((resp[1] & (byte)0x0F) == (byte)0x01) {
                                Toast.makeText(getApplicationContext(), "Wrong PIN: 1 trys left", Toast.LENGTH_LONG).show();
                                deleteAllData();
                            } else if(((resp[1] & (byte)0x0F) == (byte)0x00)){
                                showDialogPINblocked();
                                isPINblocked = true;
                            }

                        } else {

                            Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                            closeActivity();
                        }

                    }

                } else {
                    Toast.makeText(getApplicationContext(), "Wrong Input! Please try again", Toast.LENGTH_LONG).show();
                    deleteAllData();
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDialogSettings();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void changePIN()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Change PIN");

        LayoutInflater inflater = LayoutInflater.from(this);
        View layout = inflater.inflate(R.layout.change_pin_layout, null);
        builder.setView(layout);

        final EditText inputOldPIN = (EditText) layout.findViewById(R.id.oldPIN);
        final EditText inputNewPIN1 = (EditText) layout.findViewById(R.id.newPIN1);
        final EditText inputNewPIN2 = (EditText) layout.findViewById(R.id.newPIN2);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String oldPW = inputOldPIN.getText().toString();
                String newPIN1 = inputNewPIN1.getText().toString();
                String newPIN2 = inputNewPIN2.getText().toString();

                if ( myNFCTag.isConnected() ) {
                    if ( newPIN1.equals(newPIN2) ) {
                        if ( newPIN1.length() >= 4 && newPIN1.length() <= 32 ) {
                            while (oldPW.length() < 32) {
                                oldPW = oldPW + "0";
                            }

                            while (newPIN1.length() < 32) {
                                newPIN1 = newPIN1 + "0";
                            }

                            String data = "8022000220" + oldPW + newPIN1;
                            byte[] resp = sendandReciveData(apduCodes.hexToByteArray(data));
                            if(apduCodes.getResponseStatus(resp)) {
                                Toast.makeText(getApplicationContext(), "PIN successfully changed!", Toast.LENGTH_LONG).show();
                                showDialogVerify();
                            } else {
                                Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                                closeActivity();
                            }
                        } else {
                            Toast.makeText(getApplicationContext(), "Error! Passwords wrong lenght!", Toast.LENGTH_LONG).show();
                            changePIN();
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), "Error! Passwords dosen´t Match!", Toast.LENGTH_LONG).show();
                        changePIN();
                    }
                } else {
                    Toast.makeText(getApplicationContext(), "Error! Smartcard not Connected", Toast.LENGTH_LONG).show();
                    closeActivity();
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDialogSettings();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void deleteMasterPassword()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Settings");

        if ( stateMPW == (byte)0x02 ) {
            builder.setMessage("No Master Password stored!");

            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showDialogSettings();
                }
            });
        } else {
            builder.setMessage("Delete Master Password");

            builder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                    byte[] response = null;

                    if (myNFCTag.isConnected()) {
                        String data = "8032020300";
                        response = sendandReciveData(apduCodes.hexToByteArray(data));

                        if (apduCodes.getResponseStatus(response)) {
                            Toast.makeText(getApplicationContext(), "Delete Master Password successful!", Toast.LENGTH_LONG).show();
                            showDialogSettings();
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                        closeActivity();
                    }
                }
            });

            builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showDialogSettings();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    closeActivity();
                }
            });

        }

        builder.show();
    }

    private void personalizeSmartcard1()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Personalize");
        builder.setMessage("Create KeepPass Smartcard Safe\nPlese Enter your PIN\n(4-32 Characters)");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter PIN");
        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                personalizeSmartcard2(input.getText().toString());
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                closeActivity();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();

    }

    private void personalizeSmartcard2(final String pw)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Personalize");
        builder.setMessage("Please Repeat your PIN");

        final EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input1.setHint("Repeat PIN");
        input1.setFocusable(true);
        builder.setView(input1);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                if ( pw.equals( input1.getText().toString() ) ) {
                    String pw = apduCodes.checkLength(input1.getText().toString());
                    String pwl = apduCodes.StringToHex((pw.length() / 2));

                    if ( pw.length() >= 4 && pw.length() <= 32 ) {

                        if (!isPINblocked) {
                            String data = "80200001" + pwl + pw;
                            byte[] resp = sendandReciveData(apduCodes.hexToByteArray(data));
                            if(apduCodes.getResponseStatus(resp)) {
                                state = (byte)0x01;
                                showDialogPUK(resp);
                            } else {
                                Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                                closeActivity();
                            }

                        } else {
                            String resetPIN = inputPUK + pw;
                            String resetPINl = apduCodes.StringToHex(resetPIN.length() / 2);

                            String data = "80230102" + resetPINl + resetPIN;
                            byte[] resp = sendandReciveData(apduCodes.hexToByteArray(data));
                            if(apduCodes.getResponseStatus(resp)) {
                                isPINblocked = false;
                                showDialogVerify();
                            } else {
                                Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                                personalizeSmartcard1();
                            }
                        }

                    } else {
                        Toast.makeText(getApplicationContext(), "Error! Wrong Password length!", Toast.LENGTH_LONG).show();
                        personalizeSmartcard1();
                    }

                } else {
                    Toast.makeText(getApplicationContext(), "Error! Passwords dosen´t Match!", Toast.LENGTH_LONG).show();
                    personalizeSmartcard1();
                }

            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                closeActivity();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void showDialogPUK(byte[] resp)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Personalize");
        builder.setMessage("Please note your PUK\nIf you forgot your PIN");

        final EditText input1 = new EditText(this);
        input1.setClickable(false);
        input1.setFocusable(false);
        input1.setText(apduCodes.getResponseData(resp));
        builder.setView(input1);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                showDialogVerify();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
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

                            if (state == (byte)02) {
                                byte[] respName = sendandReciveData(apduCodes.apduGetFileName);

                                if (apduCodes.getResponseStatus(respName)) {
                                    cardFileName = apduCodes.dataHexToString(apduCodes.getResponseData(respName));
                                }

                            }

                            showDialogImEx();
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
                                    isPINblocked = true;
                                }

                            } else {

                                Toast.makeText(getApplicationContext(), "Error! Please try again", Toast.LENGTH_LONG).show();
                                closeActivity();
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
                closeActivity();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private int checkFileModified()
    {

        ArrayList<String> fileList = new ArrayList<>();
        int i = 0;

        File f = new File("/storage/emulated/0/keepass/");
        File[] files = f.listFiles();

        for (File inFile : files) {
            if (!inFile.isDirectory()) {
                fileList.add(i, inFile.getName());
                i++;
            }
        }

        int cardFileLength = cardFileName.length();
        String cardFileDate = cardFileName.substring(cardFileLength - 30, cardFileLength - 11);
        String cardFile = cardFileName.substring(0, cardFileLength - 31);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        Date appDate = null, cardDate = null;

        for ( String name : fileList ) {
            int nameLength = name.length();

            if( name.replace(".kdbx", "").equals(cardFile) ) {
                File file = new File("/storage/emulated/0/keepass/" + name);

                long dateModified = file.lastModified();
                Date date = new Date(dateModified);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
                String appFileDate = sdf.format(date);

                try {
                    appDate = dateFormat.parse(appFileDate);
                    cardDate = dateFormat.parse(cardFileDate);
                } catch (Exception e) {
                    Log.v(logName, "Error! Date Exception: " + e.toString());
                }

                if ( appDate.compareTo(cardDate) < 0 ) {
                    return 2;
                } else if ( appDate.compareTo(cardDate) == 0 ) {
                    return 3;
                } else {
                    return 1;
                }
            }
        }

        return 0;
    }

    private void showDialogPINblocked()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Verify");
        builder.setMessage("Your PIN is blocked!\nPlease Enter PUK");

        final EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input1.setHint("Enter PUK");
        builder.setView(input1);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                    inputPUK = input1.getText().toString();
                    inputPUK = inputPUK.replaceAll(" ","").toLowerCase();

                    if((inputPUK.length() % 2) != 0 || inputPUK.length() != 16 || !apduCodes.checkInput(inputPUK)) {
                        Toast.makeText(getApplicationContext(), "Error! Wrong Input", Toast.LENGTH_LONG).show();
                        showDialogPINblocked();
                    } else {
                        personalizeSmartcard1();
                    }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                closeActivity();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void loadDataFromSmartCard()
    {
        byte[] response = null;
        String fileSize_1 = "0000";
        String fileSize_2 = "0000";
        final String filename = "KeePass";
        final String filesuffix = ".zip";
        final String filepath = "/storage/emulated/0/keepass/";
        final String filenapa = filepath + filename +filesuffix;

        if(state == (byte)0x02) {

            if (myNFCTag.isConnected()) {
                String data = "8044030400";
                response = sendandReciveData(apduCodes.hexToByteArray(data));
            } else {
                Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                closeActivity();
            }

            if (apduCodes.getResponseStatus(response)) {
                String resp = apduCodes.getResponseData(response);
                fileSize_1 = resp.substring(0, 4);
                fileSize_2 = resp.substring(5, 8);
            }

            final String size1 = fileSize_1;
            final String size2 = fileSize_2;

            String endfilepath;
            File file = new File(filenapa);
            int o = 0;
            if (file.exists()) {
                while (file.exists()) {
                    o++;
                    endfilepath = filepath + filename + String.valueOf(o) + filesuffix;
                    file = new File(endfilepath);
                }
            }
            final String path = String.valueOf(o);

            int check = checkFileModified();
            if ( check== 0 ) {
                //File on Card is newer

                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Smartcard Import");
                builder.setMessage("Download Persistent or Tempoary");

                builder.setPositiveButton("Persistent", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        statefile = 1;
                        storeData(size1, size2);
                        dialogInterface.dismiss();
                    }
                });

                builder.setNegativeButton("Tempoary", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        statefile = 2;
                        storeData(size1, size2);
                        dialogInterface.dismiss();
                    }
                });

                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        closeActivity();
                    }
                });
                builder.show();

            } else {

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Smartcard Import");

                if ( check == 1 ) {
                    builder.setMessage("File on Phone is newer.\nDownload anyway?");
                } else if ( check == 3 ) {
                    builder.setMessage("Same File on both Devices\nDownload anyway?");
                } else {
                    builder.setMessage("File on Smartcard is newer.\nDownload anyway?");
                }

                builder.setPositiveButton("Persistent", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        statefile = 1;
                        storeData(size1, size2);
                        dialogInterface.dismiss();
                    }
                });

                builder.setNeutralButton("Tempoary", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        statefile = 2;
                        storeData(size1, size2);
                        dialogInterface.dismiss();
                    }
                });

                builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        closeActivity();
                    }
                });

                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        closeActivity();
                    }
                });
                builder.show();
            }

        }
    }

    private String writeDataToFile(String data,Context context)
    {
        File file = new File("/storage/emulated/0/keepass/zipFile.zip");
        FileOutputStream stream = null;
        String filepath = "";

        try {
            filepath = file.getPath();
            stream = new FileOutputStream(file);
        } catch (IOException e) {
            Log.e("Exception", "File write failed 1: " + e.toString());
            closeActivity();
        }

        try {
            stream.write(apduCodes.hexToByteArray(data));
            stream.close();

            String unzipName = zipKDBXFile.unzip(filepath, "/storage/emulated/0/keepass/");
            File inFile = new File("/storage/emulated/0/keepass/" + unzipName);
            InputStream in = new FileInputStream(inFile);
            OutputStream out;
            File outFile;

            if (statefile == 1) {
                filepath = inFile.getPath();
            } else {
                outFile = File.createTempFile("databaseTMP", ".kdbx", context.getCacheDir());
                out = new FileOutputStream(outFile);

                filepath = outFile.getPath();
                outFile.deleteOnExit();

                byte[] buf = new byte[1024];
                int len;

                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }

                in.close();
                out.close();

                inFile.delete();
            }

            file.delete();

        } catch (Exception e) {
            Log.e("Exception", "File write failed 2: " + e.toString());
            closeActivity();
        }

        return filepath;
    }

    private void storeData(String fileSize_1, String fileSize_2)
    {
        showDialogDownload();

        int fileSize1 = Integer.parseInt(fileSize_1, 16);
        int fileSize2 = Integer.parseInt(fileSize_2, 16);

        int fileOffset = 0, readLength = 250;
        int errorTryCounter = 3;

        Log.v(logName, "Size File 1 :" + String.valueOf(fileSize1) + " Byte");
        Log.v(logName, "Size File 2 :" + String.valueOf(fileSize2) + " Byte");
        Log.v(logName, "Start Download File 1");


        StringBuilder sb = new StringBuilder();
        boolean readDataSuccessful = true;

        while (fileOffset != fileSize1) {

            if ((fileOffset + 250) > fileSize1) {
                readLength = fileSize1 - fileOffset;
            }

            byte[] resp;

            if (myNFCTag.isConnected()) {
                String off = apduCodes.StringToHex(fileOffset);
                    if (off.length() == 2) {
                        off = "00" + off;
                    }
                String len = apduCodes.StringToHex(readLength);
                    if (len.length() == 2) {
                        len = "00" + len;
                    }
                String data = "8042030104" + off + len;
                resp = sendandReciveData(apduCodes.hexToByteArray(data));
            } else {
                Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                closeActivity();
                break;
            }

            if (apduCodes.getResponseStatus(resp)) {
                sb.append(apduCodes.getResponseData(resp));
                fileOffset = fileOffset + readLength;
            } else {
                errorTryCounter--;
            }

            if (errorTryCounter == 0) {
                readDataSuccessful = false;
                break;
            }

        }

        if (fileSize2 > 0) {
            fileOffset = 0;
            readLength = 250;
            errorTryCounter = 3;

            Log.v(logName, "Start Download File 2");

            while (fileOffset != fileSize2) {

                if ((fileOffset + 250) > fileSize2) {
                    readLength = fileSize2 - fileOffset;
                }
                byte[] resp = new byte[readLength + 2];

                if (myNFCTag.isConnected()) {
                    String off = apduCodes.StringToHex(fileOffset);
                    if (off.length() == 2) {
                        off = "00" + off;
                    }
                    String len = apduCodes.StringToHex(readLength);
                    if (len.length() == 2) {
                        len = "00" + len;
                    }
                    String data = "8042030204" + off + len;
                    resp = sendandReciveData(apduCodes.hexToByteArray(data));
                } else {
                    Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                    closeActivity();
                    break;
                }

                if (apduCodes.getResponseStatus(resp)) {
                    sb.append(apduCodes.getResponseData(resp));
                    fileOffset = fileOffset + readLength;
                } else {
                    errorTryCounter--;
                }

                if (errorTryCounter == 0) {
                    readDataSuccessful = false;
                    break;
                }

            }
        }


        if (dialog2 != null) {
            dialog2.dismiss();
        }

        dialog1.dismiss();

        if (readDataSuccessful) {
            Toast.makeText(getApplicationContext(), "File Transfer Complete", Toast.LENGTH_LONG).show();
            String pathFile = writeDataToFile(sb.toString(), this);

            if (stateMPW == 1) {
                String data = "80310202";
                byte[] response = sendandReciveData(apduCodes.hexToByteArray(data));

                if (!myNFCTag.isConnected()) {
                    Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                    closeActivity();
                } else if (apduCodes.getResponseStatus(response)) {
                    String masterPW = apduCodes.getResponseData(response);
                    String encodedPW = apduCodes.dataHexToString(masterPW);

                    Intent i = new Intent(this, PasswordActivity.class);
                    i.putExtra("password", encodedPW);
                    i.putExtra("launchImmediately", true);
                    i.setAction("android.intent.action.VIEW");
                    i.setData(UriUtil.parseDefaultFile(pathFile));
                    startActivity(i);

                    if(!isMyServiceRunning(backgroundService.class.getName())){
                        Intent intent1 = new Intent(this, backgroundService.class);
                        startService(intent1);
                    }
                }

            } else {
                try {
                    if(!isMyServiceRunning(backgroundService.class.getName())){
                        Intent intent1 = new Intent(this, backgroundService.class);
                        startService(intent1);
                    }

                    PasswordActivity.Launch(smartcardConnect.this, pathFile);
                } catch (Exception e) {
                    closeActivity();
                }
            }

        } else {
            Toast.makeText(getApplicationContext(), "File Transfer Failed! Please try again.", Toast.LENGTH_LONG).show();
            closeActivity();
        }

    }

    private void saveDataToSmartCard()
    {
        if (fileHistory.hasRecentFiles()) {
            String fileName = fileHistory.getDatabaseAt(0);
            String name = fileName.replace("file://", "");
            File fi = new File(name);
            Log.v("Smartcard: ", "File: " + name);

            if (!fi.exists()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Smartcard Export");
                builder.setMessage("File does not Exists!");

                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        closeActivity();
                    }
                });

                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        closeActivity();
                    }
                });

                builder.show();
            } else {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Smartcard Export");
                builder.setMessage("Last File:\n" + fileName);

                builder.setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        storeOnSmartcard(fileHistory.getDatabaseAt(0));
                    }
                });

                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        closeActivity();
                    }
                });

                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        closeActivity();
                    }
                });

                builder.show();
            }

        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Smartcard Export");
            builder.setMessage("No File History!\nPlease open File before\nstoring it on Smartcard");

            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    closeActivity();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    closeActivity();
                }
            });

            builder.show();
        }
    }

    private void storeOnSmartcard(String filename)
    {
        final String fileName = filename.replace("file://","");
        final String zipName;
        int filesize = 0;
        int file_1_size = 0,file_2_size = 0;

        FileInputStream fileInput = null;

        File oldFile = new File(fileName);

        long dateModified = oldFile.lastModified();
        Date date = new Date(dateModified);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String dateString = sdf.format(date);

        String oldfilename = oldFile.getName().replace(".kdbx","");
        final String newFileName = oldfilename + "-" + dateString + ".kdbx";

        String[] zipFiles = new String[1];
        zipFiles[0] = oldFile.getAbsolutePath();

        zipName = oldFile.getAbsolutePath().replace(".kdbx", ".zip");

        try {
            fileInput = new FileInputStream(oldFile.getAbsolutePath());
        } catch (Exception e) {
            Log.v("Smartcard: ", e.toString());

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Smartcard Export");
            builder.setMessage("File does not Exist!");

            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent in = new Intent();
                    in.setData(null);
                    setResult(RESULT_OK, in);
                    finish();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    closeActivity();
                }
            });

            builder.show();
        }

        try{
            zipKDBXFile.zip(zipFiles, zipName);
        } catch (Exception e) {
            Log.v(logName, "Error! Zipping: " + e.toString());
            closeActivity();
        }

        try{
            fileInput = new FileInputStream(zipName);
            filesize = (int) fileInput.getChannel().size();
        } catch (Exception e) {
            Log.v(logName, "Error! Exception: " + e.toString());
            closeActivity();
        }

        Log.v(logName, "File Size: " + String.valueOf(filesize) + "B");

        if (filesize > 50000) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Smartcard Export");
            builder.setMessage("File is too big!\nMax Size: 50KB");

            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent in = new Intent();
                    in.setData(null);
                    setResult(RESULT_OK, in);
                    finish();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    closeActivity();
                }
            });

            builder.show();
        }

        if(state == (byte)0x02) {
            int appFileLength = newFileName.length();
            int cardFileLength = cardFileName.length();

            String appFileName = oldFile.getName().replace(".kdbx", "");
            String cardFile = cardFileName.substring(0, cardFileLength - 31);
            String appFileDate = newFileName.substring(appFileLength - 24, appFileLength - 5);
            String cardFileDate = cardFileName.substring(cardFileLength - 30, cardFileLength - 11);

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            Date appDate = null, cardDate = null;

            try {
                appDate = dateFormat.parse(appFileDate);
                cardDate = dateFormat.parse(cardFileDate);
            } catch (Exception e) {
                Log.v(logName, "Error! Date Exception: " + e.toString());
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Smartcard Export");

            Log.v(logName, "File App " + appFileName);
            Log.v(logName, "File Card " + cardFile);
            if ( !appFileName.equals(cardFile) ) {
                builder.setMessage("Different File on Smartcard\nUpload anyway?");
            } else {
                if ( appDate.compareTo(cardDate) < 0 ) {
                    builder.setMessage("File on Smartcard is newer\nUpload anyway?");
                } else if ( appDate.compareTo(cardDate) == 0 ) {
                    builder.setMessage("Same File on Smartcard Found!\nPress Yes for Upload\nPress NO for cancel Upload");
                } else {
                    builder.setMessage("Older File on Smartcard found\nUpload anyway?");
                }
            }


            final int filesizeTMP = filesize;
            final int file_1_sizeTMP = file_1_size;
            final int file_2_sizeTMP = file_2_size;
            final FileInputStream fileInputTMP = fileInput;
            final String fileNameTMP = oldFile.getAbsolutePath();
            final String zipNameTMP = zipName;

            builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    String data = "80430303";
                    byte[] response = sendandReciveData(apduCodes.hexToByteArray(data));

                    if (!myNFCTag.isConnected()) {
                        Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                        closeActivity();
                    } else if (apduCodes.getResponseStatus(response)) {
                        Toast.makeText(getApplicationContext(), "File deleted!", Toast.LENGTH_LONG).show();
                        dialogInterface.dismiss();
                        uploadDataToSmartcard(filesizeTMP, file_2_sizeTMP, newFileName, fileInputTMP, fileNameTMP, zipNameTMP);
                    }
                }
            });

            builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    try {
                        File fi = new File(zipName);
                        fi.delete();
                    } catch (Exception e) {
                        Log.v(logName, "Error deleting Zip! Exception: " + e.toString());
                        closeActivity();
                    }
                    showDialogImEx();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    try {
                        File fi = new File(zipName);
                        fi.delete();
                    } catch (Exception e) {
                        Log.v(logName, "Error deleting Zip! Exception: " + e.toString());
                        closeActivity();
                    }
                    showDialogImEx();
                }
            });
            builder.setCancelable(false);
            builder.show();
        } else {

            uploadDataToSmartcard(filesize, file_2_size, newFileName, fileInput, fileName, zipName);
        }
    }

    private void uploadDataToSmartcard (int filesize, int file_2_size, String name, FileInputStream fileInput, final String fileName, String zipName)
    {
        showDialogUpload();

        int file_1_size;
        String hexSize1,hexSize2;

        if (filesize > 30000) {
            file_1_size = 30000;
            file_2_size = filesize - 30000;

            hexSize1 = apduCodes.StringToHex(file_1_size);
            hexSize1 = apduCodes.checkLength(hexSize1);

            hexSize2 = apduCodes.StringToHex(file_2_size);
            hexSize2 = apduCodes.checkLength(hexSize2);

            hexSize2 = apduCodes.checkLength(hexSize2);

            if (hexSize2.length() == 2) {
                hexSize2 = "00" + hexSize2;
            }

        } else {
            file_1_size = filesize;
            hexSize1 = apduCodes.StringToHex(filesize);
            hexSize1 = apduCodes.checkLength(hexSize1);
            hexSize2 = "0000";
        }

        String hexname = apduCodes.dataStringToHex(name);
        String hexnamelength = apduCodes.StringToHex(hexname.length() / 2 + 4);

        String data = "80400301" + hexnamelength + hexSize1 + hexSize2 + hexname;
        byte[] response = sendandReciveData(apduCodes.hexToByteArray(data));

        if (!apduCodes.getResponseStatus(response) || !myNFCTag.isConnected()) {
            Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
            return;
        }

        int dataread = 250, totalread = 0;
        boolean dataleft = true, stop = false;

        int totalOffset = 0;
        while (dataleft) {

            byte[] buff = new byte[dataread];

            try {
                fileInput.read(buff);
            } catch (Exception e) {
                Log.v(logName, "Error! Exception: " + e.toString());
                closeActivity();
            }

            String sendData = apduCodes.byteToString(buff);
            String lc = apduCodes.StringToHex(dataread +2);
            String fileoffset = apduCodes.StringToHex(totalread);

            if (fileoffset.length() == 2) {
                fileoffset = "00" + fileoffset;
            }

            String datatosend = "804103" + "01" + lc + fileoffset + sendData;
            byte[] response1 = sendandReciveData(apduCodes.hexToByteArray(datatosend));

            if (!apduCodes.getResponseStatus(response1) || !myNFCTag.isConnected()) {
                Toast.makeText(getApplicationContext(), "Error! Data Transfer canceled", Toast.LENGTH_LONG).show();
                showDialogImEx();
                return;
            }

            if (stop) {
                break;
            }

            totalread = totalread + dataread;
            totalOffset = totalOffset + dataread;

            if ((file_1_size - totalread) < 250) {
                dataread = (filesize - totalread);
                stop = true;
            }

        }

        if (file_2_size > 0) {
            dataread = 250;
            totalread = 0;
            dataleft = true;
            stop = false;

            while (dataleft) {

                if ((file_2_size - totalread) < 250) {
                    dataread = (file_2_size - totalread);
                    stop = true;
                }

                byte[] buff = new byte[dataread];
                try {
                    fileInput.read(buff);
                } catch (Exception e) {
                    Log.v(logName, "Error! Exception: " + e.toString());
                    closeActivity();
                }

                String sendData = apduCodes.byteToString(buff);

                String lc = apduCodes.StringToHex(dataread +2);
                String fileoffset = apduCodes.StringToHex(totalread);

                if (fileoffset.length() == 2) {
                    fileoffset = "00" + fileoffset;
                }

                Log.v("AppData:", sendData);
                String datatosend = "804103" + "02" + lc + fileoffset + sendData;
                byte[] response1 = sendandReciveData(apduCodes.hexToByteArray(datatosend));

                if (!apduCodes.getResponseStatus(response1) || !myNFCTag.isConnected()) {
                    Toast.makeText(getApplicationContext(), "Error! Data Transfer canceled", Toast.LENGTH_LONG).show();
                    showDialogImEx();
                    return;
                }

                if (stop) {
                    break;
                }
                totalread = totalread + dataread;
                totalOffset = totalOffset + dataread;
            }
        }

        try {
            fileInput.close();
        } catch (Exception e) {
            Log.v(logName, "Error! Exception: " + e.toString());
            closeActivity();
        }

        dialog1.dismiss();

        AlertDialog.Builder builder3 = new AlertDialog.Builder(this);
        builder3.setTitle("Smartcard Export");
        builder3.setMessage("Delete Source File?");

        builder3.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                File fi = new File(fileName);
                if (fi.delete()) {
                    Toast.makeText(getApplicationContext(), "File deleted", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), "Error! Try again", Toast.LENGTH_LONG).show();
                }

                showDialogMasterPW();
            }
        });

        builder3.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDialogMasterPW();
            }
        });

        try {
            File fi = new File(zipName);
            fi.delete();
        } catch (Exception e) {
            Log.v(logName, "Error deleting Zip! Exception: " + e.toString());
            closeActivity();
        }

        builder3.show();
    }

    private void showDialogFinishUpload()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Export");
        builder.setMessage("Upload Finished");

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                Intent in = new Intent();
                in.setData(null);
                setResult(RESULT_OK, in);
                finish();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });

        builder.show();
    }

    private void showDialogMasterPW()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Export");
        builder.setMessage("Safe Master Password on Smartcard?");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT);

        if(stateMPW == (byte)0x01) {
            input.setHint("Enter Master Password\nOld one will be overwritten");
        } else {
            input.setHint("Enter Master Password");
        }

        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String masterpw = input.getText().toString();
                masterpw = apduCodes.dataStringToHex(masterpw);
                String masterpwlength = apduCodes.StringToHex(masterpw.length() / 2);
                masterpwlength = apduCodes.checkLength(masterpwlength);

                byte[] response = null;

                if(stateMPW == (byte)0x01) {

                    if (myNFCTag.isConnected()) {
                        String data = "8032020300";
                        response = sendandReciveData(apduCodes.hexToByteArray(data));

                        if (apduCodes.getResponseStatus(response)) {
                            Log.v(logName, "Old Master Password Deleted!");
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                        closeActivity();
                    }
                }

                if (myNFCTag.isConnected()) {
                    String data = "80300201" + masterpwlength + masterpw;
                    response = sendandReciveData(apduCodes.hexToByteArray(data));
                } else {
                    Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                    closeActivity();
                }

                if (apduCodes.getResponseStatus(response)) {
                    Log.v(logName, "New Master Password saved!");
                    showDialogFinishUpload();
                }
            }
        });

        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDialogFinishUpload();
            }
        });

        builder.show();
    }

    private void closeActivity()
    {
        if(!isMyServiceRunning(backgroundService.class.getName())){
            stopService(new Intent(getBaseContext(), backgroundService.class));
        }

        Intent in = new Intent();
        in.setData(null);
        setResult(RESULT_OK, in);
        finish();
    }
}
