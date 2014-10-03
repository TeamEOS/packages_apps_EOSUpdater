
package org.teameos.updater.misc;

import android.os.SystemProperties;
import android.util.Log;

public class Logger {
    public static final boolean DEBUG = SystemProperties.getBoolean("eos.updater.debug", false);
    private static final String TAG_PRIV = "EosUpdater";

    public static void log(String tag, String msg) {
        if (DEBUG) {
            Log.i(TAG_PRIV + "/" + tag + " ", msg);
        }
    }

}
