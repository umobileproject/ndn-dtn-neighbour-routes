package net.named_data.nfd.service;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;
import android.os.SystemClock;

// IBR-DTN LIBS
import com.intel.jndn.management.ManagementException;

import android.os.Handler;
import android.os.Messenger;
import android.os.Message;

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
import net.named_data.nfd.utils.G;
import net.named_data.nfd.utils.NfdcHelper;
import net.named_data.nfd.utils.PermanentFaceUriAndRouteManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
            // invoke the dtn permanent uri creation and /ndn prefix registration
            createPermanentFaceUriAndRoute();
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

    // Permanent FACES and Routes are created here using Permanent...ger.java and NfdcHelper.java
    private void createPermanentFaceUriAndRoute() {
        final long checkInterval = 1000;
        if (m_isDtnStarted) {
            G.Log(TAG, "createPermanentFaceUriAndRoute: NFD is running, start executing task.");

            final Handler periodic_handler = new Handler ();
            periodic_handler.postDelayed(new Runnable() {
                @Override
                public void run() {

                    // Update Dtn Neighbours
                    Log.i(TAG, "(tic) UpdateDtn Neighbours");
                    Intent i = new Intent(getApplicationContext(), DtnService.class);
                    i.setAction("UpdateN");
                    i.putExtra("messenger", new Messenger(neighbourhandler));
                    startService(i);


                    boolean shouldUpd = false;
                    if (currentNeighbours != null & oldNeigs == null) {
                        shouldUpd = true;
                    }
                    if (currentNeighbours != null & oldNeigs != null) {
                        if (oldNeigs.length != currentNeighbours.length) {
                            shouldUpd = true;
                        } else {
                            for (int j = 0; j < oldNeigs.length; j++) {
                                if (oldNeigs[j] == null || currentNeighbours[j] == null) {
                                    break;
                                }
                                if (!oldNeigs[j].equals(currentNeighbours[j])) {
                                    shouldUpd = true;
                                }
                            }
                        }
                    }

                    if (shouldUpd) {
                        Log.i(TAG, "(tic) SHOULD UPDATE DTN FACES");
                        ArrayList<Integer> faceId = new ArrayList<Integer>();
                        new FaceCreateAsyncTask(currentNeighbours, getApplicationContext()).execute();
                    }

                    periodic_handler.postDelayed(this, 5000);
                }
            }, 5000);


        } else {
            G.Log(TAG, "createPermanentFaceUriAndRoute: NFD is not started yet, delay " + String.valueOf(checkInterval) + " ms.");
            prefix_handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    createPermanentFaceUriAndRoute();
                }
            }, checkInterval);
        }
    }

    /**
     * Create all DTN permanent faces in the background
     */
    private static class FaceCreateAsyncTask extends AsyncTask<Void, Void, Integer[]> {
        String [] neighbours;
        Context context;

        FaceCreateAsyncTask(String [] neighs, Context ctx) {
            this.neighbours = neighs;
            this.context = ctx;
        }

        @Override
        protected Integer[]
        doInBackground(Void... params) {
            NfdcHelper nfdcHelper = new NfdcHelper();
            ArrayList<Integer> faceId = new ArrayList<Integer>();
            try {
                G.Log(TAG, "Try to create dtn neighbour face");
                //Set<String> permanentFace = PermanentFaceUriAndRouteManager.getPermanentFaceUris(this.context);
                G.Log(TAG, "Permanent face list has " + this.neighbours.length + " item(s)");
                for (String n : neighbours) {
                    int id = nfdcHelper.faceCreate(n);
                    PermanentFaceUriAndRouteManager.addPermanentFaceId(this.context, id);
                    nfdcHelper.ribRegisterPrefix(new Name("/ndn"), id, 10, true, false);
                    faceId.add(id);
                    G.Log(TAG, "Create permanent face " + id + ": " + n + " with prefix /ndn registered");
                }
            } catch (Exception e) {
                G.Log(TAG, "Error in DTN face creation: " + e.getMessage());
            } finally {
                nfdcHelper.shutdown();
            }
            return faceId.toArray(new Integer[faceId.size()]);
        }
    }


    public String[] getNeighbours(){
        List<String> neighbours = new ArrayList<String>();
        for (Node node : this.getClient().getNeighbors()) {
            if ("NODE_INTERNET".compareTo(node.type) != 0) {
                neighbours.add(node.endpoint.toString());
            }
        }
        return neighbours.toArray(new String[neighbours.size()]);
    }

    /**
     * Filter the intent-action and do corresponding work
     * @param intent received intent
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        String[] Neighbours = null;

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

        else if (action.equals("UpdateN")) {
            //Log.i(TAG,"Update Neighbours");
            Neighbours = getNeighbours();

            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Messenger messenger = (Messenger) bundle.get("messenger");
                Message msg = Message.obtain();

                Bundle bun = new Bundle();
                bun.putStringArray("Neighbour", Neighbours);
                msg.setData(bun); //put the data here

                try {
                    messenger.send(msg);
                } catch (RemoteException e) {
                    Log.i(TAG, "error in sending neighbours");
                }
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

    final Handler neighbourhandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Bundle reply = msg.getData();
            String[] strs = reply.getStringArray("Neighbour");
            if (strs.length > 1) {
                oldNeigs = currentNeighbours;
                currentNeighbours = new String[strs.length - 1];
                currentNeighbours = Arrays.copyOfRange(strs, 1, strs.length - 1);
            } else {
                oldNeigs = currentNeighbours;
                Log.i(TAG, "oldNeigs is null");
                currentNeighbours = new String[strs.length];
                currentNeighbours = Arrays.copyOfRange(strs, 0, strs.length);
            }
        }

    };


    private String[] currentNeighbours;
    private String[] oldNeigs;
    private boolean m_isDtnStarted = false;
    private Handler prefix_handler = new Handler();

}
