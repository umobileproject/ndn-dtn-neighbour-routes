package net.named_data.nfd.service;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Binder;
import android.util.Log;
import android.os.SystemClock;

// IBR-DTN LIBS
import com.intel.jndn.management.ManagementException;

import de.tubs.ibr.dtn.api.BundleID;
import de.tubs.ibr.dtn.api.DTNClient;
import de.tubs.ibr.dtn.api.DTNClient.Session;
import de.tubs.ibr.dtn.api.DTNIntentService;
import de.tubs.ibr.dtn.api.DataHandler;
import de.tubs.ibr.dtn.api.Node;
import de.tubs.ibr.dtn.api.Registration;
import de.tubs.ibr.dtn.api.ServiceNotAvailableException;
import de.tubs.ibr.dtn.api.SessionDestroyedException;

import net.named_data.jndn.Name;
import net.named_data.jndn_xx.util.FaceUri;
import net.named_data.nfd.utils.DtnExtendedDataHandler;
import net.named_data.nfd.utils.NfdcHelper;

import java.util.List;

import de.tubs.ibr.dtn.api.SingletonEndpoint;

import static android.app.PendingIntent.getActivity;


public class DtnService extends DTNIntentService {

    static {
        System.loadLibrary("nfd-wrapper");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // IBR-DTN VARIABLES ///////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////


    private String IBRDTN_Affix = "nfd";
    public static final String ACTION_SEND_MESSAGE = "SEND_MESSAGE";

    private static final String ACTION_MARK_DELIVERED = "de.tubs.ibr.dtn.example.DELIVERED";
    private static final String EXTRA_BUNDLEID = "de.tubs.ibr.dtn.example.BUNDLEID";

    private DTNClient.Session mSession = null;
    private final IBinder mBinder = new LocalBinder();

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Native Functions ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public native void initializeNativeInterface();
    public native void queueBundleJNI(String source, byte[] payload);

    public final static String TAG = "DEBFIN_DtnService";


    public DtnService() {
        super(TAG);

    }


    public class LocalBinder extends Binder {
        public DtnService getService() {
            return DtnService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /*@Override
    public int onStartCommand(Intent intent, int flags, int startId){
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }*/
    /*
    @Override
    public void onDestroy(){
        super.onDestroy();
    }
    */
    @Override
    public void onCreate(){
        super.onCreate();
        serviceStartDtn();

        // register this Service at IBR-DTN
        Registration reg = new Registration(IBRDTN_Affix);
        try {
            initialize(reg);
        } catch (ServiceNotAvailableException e) {
            Log.i(TAG, "Service not available");
        }

    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////// IBR-DTN INTERFACE /////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Called by DtnTransport
     */
    public void sendMessage(String Destination, byte[] Payload){


            Intent i = new Intent(DtnService.this, DtnService.class);
            i.setAction(ACTION_SEND_MESSAGE);
            i.putExtra("Destination", Destination);
            i.putExtra("Data", Payload);
            startService(i);

    }


    /**
     * Filter the intent-action and do corresponding work
     * @param intent received intent
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();

        // if a new bundle was received by IBR-DTN - intent created by DtnBroadcastReceiver
        if (de.tubs.ibr.dtn.Intent.RECEIVE.equals(action)) {

            try {
                while (mSession.queryNext());
            } catch (SessionDestroyedException e) {
                Log.e(TAG, "session destroyed", e);
            } catch (NullPointerException n) {
                Log.e(TAG, "Null Pointer Exception while receiving");
            }
        }

        // if a message has to be sent - intent created by sendMessage()
        else if (ACTION_SEND_MESSAGE.equals(action)) {

            SingletonEndpoint destination = new SingletonEndpoint(intent.getStringExtra("Destination"));

            try {
                //send the given payload to destination, set bundle lifetime to 1 hour (3600 seconds)
                Log.i(TAG, ",SNDend, ," + String.valueOf(System.currentTimeMillis()));
                mSession.send(destination, 3600, intent.getByteArrayExtra("Data"));
            } catch (SessionDestroyedException e) {
                Log.e(TAG, "session destroyed", e);
            } catch (NullPointerException n) {
                Log.e(TAG, "Null Pointer Exception while sending");
            }
        }

        //if a received bundle should be marked as delivered
        else if (ACTION_MARK_DELIVERED.equals(action) ) {
            try {
                //get id of bundle to mark
                BundleID id = intent.getParcelableExtra(EXTRA_BUNDLEID);
                if (id != null) mSession.delivered(id);
            } catch (SessionDestroyedException e) {
                Log.e(TAG, "session destroyed", e);
            }
        }
    }

    /**
     * called if service is connected to IBR-DTN
     * saves the session and sets DataHandler processing bundles
     *
     * @param session current Session
     */
    @Override
    protected void onSessionConnected(Session session) {
        mSession = session;
        mSession.setDataHandler(mDataHandler);
    }

    @Override
    protected void onSessionDisconnected() {
        mSession = null;
    }

    /**
     * Notice: The ExtendedDataHandler only supports messages with
     * a maximum size of 4096 bytes. Bundles of other sizes are
     * not going to be delivered.
     * If a higher limit is set, bundles received will be fragmented
     * and they won't be marked as delivered properly
     */
    private DataHandler mDataHandler = new DtnExtendedDataHandler() {
        @Override
        protected void onMessage(BundleID id, byte[] data) {

            String source = id.getSource().toString();

            byte[] by = data;

            String temp = source.substring(6);
            String[] splited = temp.split("/");

            queueBundleJNI("dtn://" + splited[0],by);

            // mark the bundle as delivered
            Intent i = new Intent(DtnService.this, DtnService.class);
            i.setAction(ACTION_MARK_DELIVERED);
            i.putExtra(EXTRA_BUNDLEID, id);
            startService(i);
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    /////////////////////////////////// NFD INTERFACE //////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /*
     * Gives NFD the necessary JavaVM pointer for later calls
     */
    private void
    serviceStartDtn() {
        if (!m_isDtnStarted) {
            m_isDtnStarted = true;
            //Log.i(TAG, ",SNDend, ," + String.valueOf(System.nanoTime()));
            initializeNativeInterface();
            //Log.i(TAG, ",SNDend, ," + String.valueOf(System.nanoTime()));
            /*
            List<Node> nodes = getClient().getNeighbors();
            for (Node node : nodes){
                Log.e(TAG, "node found: " + node.toString());
            }
            */
        }

    }


    private boolean m_isDtnStarted = false;
}
