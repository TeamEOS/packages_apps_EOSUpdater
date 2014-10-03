
package org.teameos.updater.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.teameos.updater.misc.Logger;
import org.teameos.updater.misc.UpdateInfo;
import org.teameos.updater.utils.Utils;

import org.teameos.updater.R;

import android.content.Context;
import android.util.Log;

public class EosQueryParser extends BaseQueryParser {
    private static final String TAG = EosQueryParser.class.getSimpleName();
    private static final boolean DEBUG = true;

    private String base_url;
    private String file_list_url;

    public EosQueryParser() {
    }

    @Override
    public void init(Context context) {
        super.init(context);
        base_url = mContext.getString(R.string.conf_update_server_base_url);
        file_list_url = mContext.getString(R.string.conf_update_server_file_url);
    }

    @Override
    public LinkedList<UpdateInfo> getUpdateInfo() {
        LinkedList<UpdateInfo> infos = new LinkedList<UpdateInfo>();
        try {
            String query = getQueryUrl();
            log("Attempting query with " + query);
            HttpGet request = new HttpGet(query);
            addRequestHeaders(request);
            HttpEntity entity = executeQuery(request);
            String result = null;
            if (entity != null) {
                InputStream instream = entity.getContent();
                result = convertStreamToString(instream);
                instream.close();
            }
            if (result == null) {
                log("Raw query result was null. Returning empty list");
                return infos; // empty list is better than null
            }
            JSONObject baseObj = new JSONObject(result);

            String serverMsg = baseObj.getString("result");
            JSONObject data = baseObj.getJSONObject("data");
            if ("failed".equals(serverMsg)) {
                String theFail = data.getString("message");
                log("Server returned fail message: " + theFail);
                return infos;
            }

            JSONArray fileList = data.getJSONArray("file_list");
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject file = fileList.getJSONObject(i);
                long epoch = file.getLong("epoch");
                String name = file.getString("name");
                int sdk = 19;
                String url = base_url + file.getString("url");
                String md5 = file.getString("md5sum");
                UpdateInfo info = new UpdateInfo(name, epoch, sdk, url, md5,
                        UpdateInfo.Type.NIGHTLY);
                log("File " + String.valueOf(i) + " : " + file.toString());
                infos.add(info);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return infos;
    }

    private String getQueryUrl() {
        StringBuilder b = new StringBuilder();
        b.append(base_url);
        b.append(file_list_url);
        b.append("?owner=");
        b.append("eos");
        b.append("&size=");
        b.append(5); // max history = 5
        b.append("&device=");
        b.append(Utils.getDeviceType());
        if (Logger.DEBUG) {
            b.append("&info=device,id,date,epoch,owner,name,version,url,size,download_count,md5sum,old_version");
        } else {
            b.append("&info=epoch,url,md5sum");
        }
        String url = b.toString();
        return url;
    }

    private static String convertStreamToString(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }

    private static void log(String msg) {
        Logger.log(TAG, msg);
    }

}
