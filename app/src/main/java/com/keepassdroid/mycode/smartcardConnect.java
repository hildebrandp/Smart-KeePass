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
import android.widget.EditText;
import android.widget.Toast;
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
 * Created by hilde on 29.05.2017.
 *
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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        fileHistory = App.getFileHistory();

        mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);

        try {
            ndef.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("fail", e);
        }

        mFilters = new IntentFilter[] { ndef, };
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

        isFirstConnect = true;
        showDialogConnect();
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
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
                tagID = apduCodes.byteToHexString(t.getId());

                if(!isMyServiceRunning(backgroundService.class.getName())){
                    Intent intent1 = new Intent(this, backgroundService.class);
                    startService(intent1);
                }

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

    private boolean isMyServiceRunning(String className)
    {
        ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (className.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void connectToCard()
    {
        byte[] resp = sendandReciveData(apduCodes.apduSelectApplet);

        if (resp != null) {

            if(apduCodes.getResponseCode(resp).equals("9000")) {
                isFirstConnect = false;
                state = resp[0];
                Toast.makeText(getApplicationContext(), "Connected with Smartcard!", Toast.LENGTH_LONG).show();
                dialog1.cancel();

                if(state == (byte)0x00) {
                    personalizeSmartcard1();
                } else {
                    showDialogVerify();
                }

            } else if(apduCodes.getResponseCode(resp).equals("6250")){
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

    private void showDialogConnect()
    {
        dialog1 = new AlertDialog.Builder(this).create();
        dialog1.setTitle("Smartcard Support");
        dialog1.setMessage("Please keep your Card!");

        dialog1.show();
    }

    private void showDialogImEx()
    {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smartcard Support");

        if (state == (byte)0x00) {
            builder.setMessage("Do you want to Personalize\nyour Smartcard?");
            builder.setPositiveButton("Create", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    personalizeSmartcard1();
                }
            });
        }

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

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                closeActivity();
            }
        });
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
                            Log.v(logNameSend, data);
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
                            Log.v(logNameSend, data);
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

    private boolean checkFileModified()
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

        String cardFileDate = cardFileName.replace("KeePassDatabase-", "");
        String appFileDate;

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        Date appDate = null, cardDate = null;

        for (String name : fileList) {
            if(name.substring(0, 16).equals("KeePassDatabase-")) {
                appFileDate = name.replace("KeePassDatabase-", "");

                try {
                    appDate = dateFormat.parse(appFileDate);
                    cardDate = dateFormat.parse(cardFileDate);
                } catch (Exception e) {
                    Log.v(logName, "Error! Date Exception: " + e.toString());
                }

                if (appDate.compareTo(cardDate) < 0) {

                    return true;

                }
            }
        }

        return false;
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

            if (checkFileModified()) {
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
                builder.setMessage("File on Phone is newer.\nDownload anyway?");

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

            if(statefile == 1) {

                int random = (int )(Math.random() * 999 + 1);
                outFile = new File("/storage/emulated/0/keepass/" + String.valueOf(random) + unzipName);
                outFile.createNewFile();
                out = new FileOutputStream(outFile);
                filepath = outFile.getPath();

            } else {

                outFile = File.createTempFile("databaseTMP", ".kdbx", context.getCacheDir());
                out = new FileOutputStream(outFile);

                filepath = outFile.getPath();
                outFile.deleteOnExit();
            }

            byte[] buf = new byte[1024];
            int len;

            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }

            in.close();
            out.close();

            inFile.delete();
            file.delete();

        } catch (Exception e) {
            Log.e("Exception", "File write failed 2: " + e.toString());
            closeActivity();
        }

        return filepath;
    }

    private void storeData(String fileSize_1, String fileSize_2)
    {
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

        if (readDataSuccessful) {
            Toast.makeText(getApplicationContext(), "File Transfer Complete", Toast.LENGTH_LONG).show();
            String pathFile = writeDataToFile(sb.toString(), this);

            Toast.makeText(getApplicationContext(), pathFile, Toast.LENGTH_LONG).show();

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
                }

            } else {
                try {
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
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Smartcard Export");
                builder.setMessage("Last File:\n" + fileName);

                builder.setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
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

        final String newFileName = "KeePassDatabase-" + dateString + ".kdbx";
        File newFile = new File(oldFile.getParent(), newFileName);

        if (oldFile.renameTo(newFile)) {
            Log.v("Smartcard: ", "Rename File success");

            System.gc();
            Thread.yield();

            fileHistory.deleteFile(UriUtil.parseDefaultFile(filename));
        }

        String[] zipFiles = new String[1];
        zipFiles[0] = newFile.getAbsolutePath();

        zipName = newFile.getAbsolutePath().replace(".kdbx", ".zip");

        try {
            fileInput = new FileInputStream(newFile.getAbsolutePath());
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
            String appFileDate = newFile.getName().replace("KeePassDatabase-", "");
            String cardFileDate = cardFileName.replace("KeePassDatabase-", "");

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

            if (appDate.compareTo(cardDate) < 0) {
                builder.setMessage("File on Smartcard is newer\nDelete anyway?");
            } else if (appDate.compareTo(cardDate) == 0) {
                builder.setMessage("Same File on Smartcard Found!\nNo need for Upload.\nPress NO for cancel Upload");
            } else {
                builder.setMessage("Older File on Smartcard found\nDelete?");
            }

            final int filesizeTMP = filesize;
            final int file_1_sizeTMP = file_1_size;
            final int file_2_sizeTMP = file_2_size;
            final FileInputStream fileInputTMP = fileInput;
            final String fileNameTMP = newFile.getAbsolutePath();
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
                        uploadDataToSmartcard(filesizeTMP, file_2_sizeTMP, newFileName, fileInputTMP, fileNameTMP, zipNameTMP);
                    }
                }
            });

            builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    showDialogImEx();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
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
        AlertDialog builder = new AlertDialog.Builder(this).create();
        builder.setTitle("Smartcard Export");
        builder.setMessage("Creating File...");
        builder.show();

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

        String data = "804003012C" + hexSize1 + hexSize2 + apduCodes.dataStringToHex(name);
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

        builder.dismiss();

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
                fileHistory.createFile(UriUtil.parseDefaultFile("file://" + fileName), null);
                showDialogMasterPW();
            }
        });

        try {
            File fi = new File(zipName);
            fi.delete();
        } catch (Exception e) {
            Log.v(logName, "Error! Exception: " + e.toString());
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
                    } else {
                        Toast.makeText(getApplicationContext(), "Error! No Connection", Toast.LENGTH_LONG).show();
                        closeActivity();
                    }

                    if (apduCodes.getResponseStatus(response)) {
                        Log.v(logName, "Old Master Password Deleted!");
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
