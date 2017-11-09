package net.named_data.nfd.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import net.named_data.nfd.service.DtnService;
/**
 * BroadcastReceiver for Intents from IBR-DTN
 */

public class DtnBroadcastReceiver extends BroadcastReceiver {

    //String TAG = "DEBFIN";
    @Override
    public void onReceive(Context context, Intent intent) {

        //Log.i(TAG,",RCVstart, , ,"+String.valueOf(System.currentTimeMillis()));
        String action = intent.getAction();
        if (action.equals(de.tubs.ibr.dtn.Intent.RECEIVE))
        {
            Intent i = new Intent(context, DtnService.class);
            i.setAction(de.tubs.ibr.dtn.Intent.RECEIVE);
            context.startService(i);
        }
    }

}
