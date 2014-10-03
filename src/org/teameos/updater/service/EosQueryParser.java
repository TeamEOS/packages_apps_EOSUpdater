
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
import org.teameos.updater.misc.UpdateInfo;
import org.teameos.updater.utils.Utils;

import org.teameos.updater.R;

import android.content.Context;

public class EosQueryParser extends BaseQueryParser {
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
            HttpGet request = new HttpGet(getQueryUrl());
            addRequestHeaders(request);
            HttpEntity entity = executeQuery(request);
            String result = null;
            if (entity != null) {
                InputStream instream = entity.getContent();
                result = convertStreamToString(instream);
                instream.close();
            }
            if (result == null) {
                return infos;
            }
            JSONObject baseObj = new JSONObject(result);
            JSONObject data = baseObj.getJSONObject("data");
            JSONArray fileList = data.getJSONArray("file_list");
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject file = fileList.getJSONObject(i);
                int id = file.getInt("id");
                // String date = file.getString("date");
                long epoch = file.getLong("epoch");
                // String owner = file.getString("owner");
                // String device = file.getString("device");
                String name = file.getString("name");
                // String version = file.getString("version");
                // int sdk = file.getInt("sdk"); not on deck yet
                int sdk = 19;
                String url = base_url + file.getString("url");
                // String size = file.getString("size");
                // String dlCount = file.getString("download_count");
                String md5 = file.getString("md5sum");
                // String oldVersion = file.getString("old_version");
                UpdateInfo info = new UpdateInfo(name, epoch, sdk, url, md5,
                        UpdateInfo.Type.NIGHTLY);
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
        b.append("?start=");
        b.append(0); // start with newest build
        b.append("&size=");
        b.append(5); // max history = 5
        b.append("&device=");
        b.append(Utils.getDeviceType());
        b.append("&info=device,id,date,epoch,owner,name,version,url,size,download_count,md5sum,old_version");
        return b.toString();
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

}
