/*
 *
 * Developed by Pascal Hildebrand.
 *
 * This file is part of Smart KeePass.
 * Smart KeePass is an Further development of KeePassDroid from Brian Pellin.
 * KeePassDroid is available under www.keepassdroid.com, Copyright Brian Pellin.
 *
 *  Smart KeePass is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  Smart KeePass is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Smart KeePass.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepassdroid.smartcardSupport;


import android.app.Service;
import android.content.Intent;
import android.nfc.tech.IsoDep;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Background Service Class which checks if Smartcard is still connected
 * If Card isn´t connected anymore it send Message to Open Class
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

    /**
     * Method which starts Timer with interval of 5 seconds
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        startTimer(5000);
        return START_STICKY;
    }

    /**
     * Timer Method
     * If dosen´t exists start new Timer
     * else stop old timer and start new
     * @param time interval
     */
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

    /**
     * Timer Task which is called in the interval of 5 seconds
     * The Method checks if the Smartcard is connected
     * If Card is disconnected it sends Broadcast
     */
    class checkConnection extends TimerTask
    {
        @Override
        public void run()
        {
            mHandler.post(new Runnable() {
                @Override
                public void run()
                {
                    if ( myNFCTag != null ) {
                        if ( !myNFCTag.isConnected() ) {
                            Log.v("App:", "Smartcard no connection!");
                            Intent intent = new Intent(NOTIFICATION_CLOSE);
                            intent.putExtra("RESULT", "TRUE");
                            sendBroadcast(intent);

                            Toast.makeText(getApplicationContext(), "Smartcard disconnect!", Toast.LENGTH_LONG).show();
                            mTimer.cancel();
                        }
                    } else {
                        Log.v("App:", "Smartcard no connection!");
                        Intent intent = new Intent(NOTIFICATION_CLOSE);
                        intent.putExtra("RESULT", "TRUE");
                        sendBroadcast(intent);

                    }


                }

            });
        }
    }

}
