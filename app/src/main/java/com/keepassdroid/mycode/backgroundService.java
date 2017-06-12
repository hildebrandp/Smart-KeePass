package com.keepassdroid.mycode;


import android.app.Service;
import android.content.Intent;
import android.nfc.tech.IsoDep;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.keepassdroid.app.App;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by hilde on 12.06.2017.
 */

public class backgroundService extends Service
{
    private final IBinder mBinder = new MyBinder();
    private Timer mTimer = null;
    private Handler mHandler = new Handler();
    private static IsoDep myNFCTag;
    public static final String NOTIFICATION_CLOSE = "CLOSE";

    @Override
    public IBinder onBind(Intent arg0)
    {
        return mBinder;
    }

    public class MyBinder extends Binder
    {
        backgroundService getService()
        {
            return backgroundService.this;
        }
    }

    @Override
    public void onCreate()
    {
        myNFCTag = smartcardConnect.myNFCTag;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        startTimer(5000);
        return START_STICKY;
    }

    public void startTimer(int time)
    {
        if( mTimer != null ) {
            mTimer.cancel();
            mTimer = new Timer();
        } else {
            mTimer = new Timer();
        }

        mTimer.scheduleAtFixedRate(new checkConnection(), 1000, time);
        Log.v("App:", "Service running!");
    }

    class checkConnection extends TimerTask
    {
        @Override
        public void run()
        {
            mHandler.post(new Runnable() {
                @Override
                public void run()
                {
                    if ( !myNFCTag.isConnected() ) {
                        Log.v("App:", "Smartcard no connection!");
                        Intent intent = new Intent(NOTIFICATION_CLOSE);
                        intent.putExtra("RESULT", "TRUE");
                        sendBroadcast(intent);

                        Toast.makeText(getApplicationContext(), "Smartcard disconnect!", Toast.LENGTH_LONG).show();
                    }

                }

            });
        }
    }

}
