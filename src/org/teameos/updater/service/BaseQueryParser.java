
package org.teameos.updater.service;

import java.io.IOException;
import java.util.LinkedList;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.teameos.updater.misc.UpdateInfo;
import org.teameos.updater.utils.Utils;

import android.content.Context;
import android.util.Log;

public abstract class BaseQueryParser {
    private static final String TAG = BaseQueryParser.class.getSimpleName();

    private HttpRequestExecutor mExecutor = null;
    protected Context mContext;

    public BaseQueryParser() {
    }

    protected synchronized void onAbort() {
    }

    protected synchronized boolean onIsAborted() {
        return false;
    }

    protected void addRequestHeaders(HttpRequestBase request) {
        String userAgent = Utils.getUserAgentString(mContext);
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        request.addHeader("Cache-Control", "no-cache");
    }

    protected HttpEntity executeQuery(HttpRequestBase request) {
        if (request == null) {
            Log.e(TAG, "Null executeQuery request from subclass");
            return null;
        }
        if (mExecutor == null) {
            mExecutor = new HttpRequestExecutor();
        }
        try {
            return mExecutor.execute(request);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            Log.e(TAG, "Error executing query");
            return null;
        }
    }

    public void init(Context context) {
        mContext = context;
    }

    public abstract LinkedList<UpdateInfo> getUpdateInfo();

    public boolean isAborted() {
        boolean aborted = false;
        if (mExecutor != null) {
            aborted = mExecutor.isAborted();
        }
        return aborted && onIsAborted();
    }

    public void abort() {
        if (mExecutor != null) {
            mExecutor.abort();
        }
        onAbort();
    }

    private static class HttpRequestExecutor {
        private HttpClient mHttpClient;
        private HttpRequestBase mRequest;
        private boolean mAborted;

        public HttpRequestExecutor() {
            mHttpClient = new DefaultHttpClient();
            mAborted = false;
        }

        HttpEntity execute(HttpRequestBase request) throws IOException {
            synchronized (this) {
                mAborted = false;
                mRequest = request;
            }

            HttpResponse response = mHttpClient.execute(request);
            HttpEntity entity = null;

            if (!mAborted && response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                entity = response.getEntity();
            }

            synchronized (this) {
                mRequest = null;
            }

            return entity;
        }

        synchronized void abort() {
            if (mRequest != null) {
                mRequest.abort();
            }
            mAborted = true;
        }

        synchronized boolean isAborted() {
            return mAborted;
        }
    }
}
