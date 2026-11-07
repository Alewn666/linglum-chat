package com.linglum.chat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 客户端（HttpURLConnection，不引第三方库） */
public final class Api {

    /** 当前 App 版本号（由 MainActivity 启动时写入），服务端用它判断要不要强制更新 */
    public static int APP_VERSION = 0;

    private final String base;
    private final String token;

    public Api(String base, String token) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
    }

    public JSONObject get(String path) throws Exception {
        return call("GET", path, null, 15000);
    }

    /** 长轮询用：读超时要大于服务端挂起时间（25s） */
    public JSONObject getLong(String path) throws Exception {
        return call("GET", path, null, 40000);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return call("POST", path, body, 15000);
    }

    public JSONObject delete(String path) throws Exception {
        return call("DELETE", path, null, 15000);
    }

    /** 上传加密后的文件字节，返回 fid */
    public JSONObject uploadFile(byte[] data, String name, String toType, String toId) throws Exception {
        String path = "/api/files?to_type=" + java.net.URLEncoder.encode(toType, "UTF-8")
                + "&to_id=" + java.net.URLEncoder.encode(toId, "UTF-8")
                + "&name=" + java.net.URLEncoder.encode(name, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (APP_VERSION > 0) c.setRequestProperty("X-App-Version", String.valueOf(APP_VERSION));
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        c.disconnect();
        JSONObject out = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        out.put("_http", code);
        return out;
    }

    /** 取二进制（头像等）。404 → null（表示没有头像） */
    public byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (APP_VERSION > 0) c.setRequestProperty("X-App-Version", String.valueOf(APP_VERSION));
        int code = c.getResponseCode();
        if (code == 404) {
            c.disconnect();
            return null;
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }

    /** 下载文件（密文） */
    public byte[] downloadFile(String fid) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/files/" + fid).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (APP_VERSION > 0) c.setRequestProperty("X-App-Version", String.valueOf(APP_VERSION));
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("下载失败 HTTP " + code);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }

    private JSONObject call(String method, String path, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (APP_VERSION > 0) {
            c.setRequestProperty("X-App-Version", String.valueOf(APP_VERSION));
        }
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        c.disconnect();

        JSONObject out;
        try {
            out = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            android.util.Log.w("LinglumChat", "响应解析失败 HTTP " + code);
            out = new JSONObject();
            out.put("error", "服务器返回异常（HTTP " + code + "）");
        }
        out.put("_http", code);
        if (code >= 400 && !out.has("error")) out.put("error", "HTTP " + code);
        return out;
    }
}
