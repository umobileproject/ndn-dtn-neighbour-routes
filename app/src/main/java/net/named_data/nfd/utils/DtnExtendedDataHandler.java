package net.named_data.nfd.utils;

import android.os.ParcelFileDescriptor;
import de.tubs.ibr.dtn.api.Block;
import de.tubs.ibr.dtn.api.Bundle;
import de.tubs.ibr.dtn.api.BundleID;
import de.tubs.ibr.dtn.api.DataHandler;
import de.tubs.ibr.dtn.api.TransferMode;


public abstract class DtnExtendedDataHandler implements DataHandler{
    private Bundle mBundle = null;

    public DtnExtendedDataHandler() {
    }

    protected abstract void onMessage(BundleID var1, byte[] var2);

    public void startBundle(Bundle bundle) {
        this.mBundle = bundle;
    }

    public void endBundle() {
        this.mBundle = null;
    }

    public TransferMode startBlock(Block block) {
        return block.type.intValue() == 1 && block.length.longValue() <= 4096L?TransferMode.SIMPLE:TransferMode.NULL;
    }

    public void endBlock() {
    }

    public void payload(byte[] data) {
        this.onMessage(new BundleID(this.mBundle), data);
    }

    public ParcelFileDescriptor fd() {
        return null;
    }

    public void progress(long current, long length) {
    }

}
