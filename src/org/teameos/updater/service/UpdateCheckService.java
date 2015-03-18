/*
 * Copyright (C) 2012 The Vanir Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package org.teameos.updater.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.teameos.updater.R;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.teameos.updater.UpdateApplication;
import org.teameos.updater.UpdatesSettings;
import org.teameos.updater.misc.Constants;
import org.teameos.updater.misc.Logger;
import org.teameos.updater.misc.State;
import org.teameos.updater.misc.UpdateInfo;
import org.teameos.updater.receiver.DownloadReceiver;
import org.teameos.updater.utils.Utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;

public class UpdateCheckService extends IntentService {
    private static final String TAG = "UpdateCheckService";

    // Set this to true if the update service should check for smaller, test updates
    // This is for internal testing only
    private static final boolean TESTING_DOWNLOAD = false;

    // request actions
    public static final String ACTION_CHECK = "org.teameos.updater.action.CHECK";
    public static final String ACTION_CANCEL_CHECK = "org.teameos.updater.action.CANCEL_CHECK";

    // broadcast actions
    public static final String ACTION_CHECK_FINISHED = "org.teameos.updater.action.UPDATE_CHECK_FINISHED";
    // extra for ACTION_CHECK_FINISHED: total amount of found updates
    public static final String EXTRA_UPDATE_COUNT = "update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that are newer than what is installed
    public static final String EXTRA_REAL_UPDATE_COUNT = "real_update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that were found for the first time
    public static final String EXTRA_NEW_UPDATE_COUNT = "new_update_count";

    // max. number of updates listed in the expanded notification
    private static final int EXPANDED_NOTIF_UPDATE_COUNT = 4;

    //private HttpRequestExecutor mHttpExecutor;
    private HttpClient client = new DefaultHttpClient();
    private BaseQueryParser mParser;

    public UpdateCheckService() {
        super("UpdateCheckService");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TextUtils.equals(intent.getAction(), ACTION_CANCEL_CHECK)) {
            /*synchronized (this) {
                if (mHttpExecutor != null) {
                    mHttpExecutor.abort();
                }
            }*/

            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final Resources res = getResources();

        final boolean fromQuicksettings = intent.hasExtra("isFromQuicksettings");

        UpdateApplication app = (UpdateApplication) getApplicationContext();
        final boolean updaterIsForeground = app.isMainActivityActive();

        if (!Utils.isOnline(this)) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "Could not check for updates. Not connected to the network.");
            if (!updaterIsForeground) {
                final Context mContext = getApplicationContext();
                final String cheese = mContext.getString(R.string.update_check_failed);
                Toast.makeText(mContext, cheese, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Set up a progressbar notification
        final int progressID = 1;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (fromQuicksettings) {
            Notification.Builder progress = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.cm_updater)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(res.getString(R.string.checking_for_updates))
                    .setContentTitle(res.getString(R.string.checking_for_updates))
                    .setProgress(0, 0, true);
            // Trigger the progressbar notification
            nm.notify(progressID, progress.build());
        }

        // load our query parser class
        final String clsName = getString(R.string.conf_project_update_list_class);
        log("trying to load class " + String.valueOf(clsName));
        if (clsName == null || clsName.length() == 0) {
            log("Could not get query parser class name from config");
            return;
        }
        Class<?> cls = null;
        try {
            cls = getClassLoader().loadClass(clsName);
            log("Sucessfully loaded class " + String.valueOf(clsName));
        } catch (Throwable t) {
            log("Could not load query parser class");
            return;
        }
        try {
        mParser = (BaseQueryParser) cls.newInstance();
        log("Trying to initialize " + String.valueOf(clsName));
        } catch (Exception e){
            log("Failed to initialize " + String.valueOf(clsName));
            return;
        }
        if (mParser == null) {
            log("Could not create instance query parser class");
            return;
        }
        log("Sucessfully initialized class " + String.valueOf(clsName));
        mParser.init(this);

        // Start the update check
        Intent finishedIntent = new Intent(ACTION_CHECK_FINISHED);
        LinkedList<UpdateInfo> availableUpdates;
        try {
            availableUpdates = getAvailableUpdatesAndFillIntent(finishedIntent);
        } catch (IOException e) {
            Log.e(TAG, "Could not check for updates", e);
            availableUpdates = null;
        }

        if (availableUpdates == null) {// || mHttpExecutor.isAborted()) {
            if (fromQuicksettings) nm.cancel(progressID);
            sendBroadcast(finishedIntent);
            return;
        }

        // Store the last update check time and ensure boot check completed is true
        Date d = new Date();
        PreferenceManager.getDefaultSharedPreferences(UpdateCheckService.this).edit()
                .putLong(Constants.LAST_UPDATE_CHECK_PREF, d.getTime())
                .putBoolean(Constants.BOOT_CHECK_COMPLETED, true)
                .apply();

        int realUpdateCount = finishedIntent.getIntExtra(EXTRA_REAL_UPDATE_COUNT, 0);

        // Write to log
        Log.i(TAG, "The update check successfully completed at " + d + " and found "
                + availableUpdates.size()  + " updates ("
                + realUpdateCount + " newer than installed)");

        if (realUpdateCount == 0 && fromQuicksettings) {

            Intent i = new Intent(this, UpdatesSettings.class);
            i.putExtra(UpdatesSettings.EXTRA_UPDATE_LIST_UPDATED, true);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_ONE_SHOT);

            // Get the notification ready
            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.cm_updater)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(res.getString(R.string.no_updates_found))
                    .setContentTitle(res.getString(R.string.no_updates_found))
                    .setContentText(res.getString(R.string.no_updates_found_body))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true);
            // Trigger the notification
            nm.cancel(progressID);
            nm.notify(R.string.no_updates_found, builder.build());

            sendBroadcast(finishedIntent);
            return;
        }

        if (realUpdateCount != 0 && !updaterIsForeground) {

            Intent i = new Intent(this, UpdatesSettings.class);
            i.putExtra(UpdatesSettings.EXTRA_UPDATE_LIST_UPDATED, true);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_ONE_SHOT);

            String text = res.getQuantityString(R.plurals.not_new_updates_found_body,
                    realUpdateCount, realUpdateCount);

            // Get the notification ready
            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.cm_updater)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(res.getString(R.string.not_new_updates_found_ticker))
                    .setContentTitle(res.getString(R.string.not_new_updates_found_title))
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true);

            LinkedList<UpdateInfo> realUpdates = new LinkedList<UpdateInfo>();
            for (UpdateInfo ui : availableUpdates) {
                if (ui.isNewerThanInstalled()) {
                    realUpdates.add(ui);
                }
            }

            Collections.sort(realUpdates, new Comparator<UpdateInfo>() {
                @Override
                public int compare(UpdateInfo lhs, UpdateInfo rhs) {
                    /* sort by date descending */
                    long lhsDate = lhs.getDate();
                    long rhsDate = rhs.getDate();
                    if (lhsDate == rhsDate) {
                        return 0;
                    }
                    return lhsDate < rhsDate ? 1 : -1;
                }
            });

            Notification.InboxStyle inbox = new Notification.InboxStyle(builder)
                    .setBigContentTitle(text);
            int added = 0, count = realUpdates.size();

            for (UpdateInfo ui : realUpdates) {
                if (added < EXPANDED_NOTIF_UPDATE_COUNT) {
                    inbox.addLine(ui.getName());
                    added++;
                }
            }
            if (added != count) {
                inbox.setSummaryText(res.getQuantityString(R.plurals.not_additional_count,
                            count - added, count - added));
            }
            builder.setStyle(inbox);
            builder.setNumber(availableUpdates.size());

            if (count == 1) {
                i = new Intent(this, DownloadReceiver.class);
                i.setAction(DownloadReceiver.ACTION_START_DOWNLOAD);
                i.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) realUpdates.getFirst());
                PendingIntent downloadIntent = PendingIntent.getBroadcast(this, 0, i,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);

                builder.addAction(R.drawable.ic_tab_download,
                        res.getString(R.string.not_action_download), downloadIntent);
            }

            // Trigger the notification
            if (fromQuicksettings) nm.cancel(progressID);
            nm.notify(R.string.not_new_updates_found_title, builder.build());
        }

        sendBroadcast(finishedIntent);
    }

    private LinkedList<UpdateInfo> getAvailableUpdatesAndFillIntent(Intent intent) throws IOException {
        // Get the type of update we should check for
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //TODO handle releases too!
        int updateType = prefs.getInt(Constants.UPDATE_TYPE_PREF, 0);

        LinkedList<UpdateInfo> lastUpdates = State.loadState(this);
        LinkedList<UpdateInfo> updates = mParser.getUpdateInfo();

        int newUpdates = 0, realUpdates = 0;
        for (UpdateInfo ui : updates) {
            if (!lastUpdates.contains(ui)) {
                newUpdates++;
            }
            if (ui.isNewerThanInstalled()) {
                realUpdates++;
            }
        }
        Log.d(TAG, "Found: "+newUpdates+" NEW and "+realUpdates+" REAL updates");

        intent.putExtra(EXTRA_UPDATE_COUNT, updates.size());
        intent.putExtra(EXTRA_REAL_UPDATE_COUNT, realUpdates);
        intent.putExtra(EXTRA_NEW_UPDATE_COUNT, newUpdates);

        State.saveState(this, updates);

        return updates;
    }

    private static void log(String msg) {
        Logger.log(TAG, msg);
    }
}