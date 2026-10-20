package com.homepad.wallpadbridge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal loopback client for the stock COMMAX media controller (CMXMM).
 *
 * The vendor SIP process already talks to this gSOAP service on 127.0.0.1:29720.
 * Using the same local protocol avoids loading a second copy of the vendor JNI
 * library into the bridge process. Calls are limited to the unit-entrance
 * audio relay and the stock unit-door button's check event.
 */
final class CmxMediaControl {
    private static final String TAG = "HomePadCmxAudio";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LOOPBACK = "127.0.0.1";
    private static final int PORT = 29720;
    private static final Pattern OUT = Pattern.compile("<out>(-?[0-9]+)</out>");

    private CmxMediaControl() { }

    static int getReferenceCount() throws Exception {
        return invoke("getRef", new LinkedHashMap<String, String>());
    }

    static void requestUnitDoorOpen() throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("nCheckValue", "33");
        values.put("chDummy", "");
        int result = invoke("reqCheckEvent", values);
        if (result != 0) throw new IllegalStateException("reqCheckEvent rejected " + result);
    }

    static int getAudioPath() throws Exception {
        return invoke("getAudioPath2", new LinkedHashMap<String, String>());
    }

    static void setAudioPath(int path) throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("in", Integer.toString(path));
        int result = invoke("audioPath2", values);
        if (result != 0) throw new IllegalStateException("audioPath2 rejected " + result);
    }

    static int startRtpOneChannel(int localPort, int remotePort) throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("local-port", Integer.toString(localPort));
        values.put("remote-ip", LOOPBACK);
        values.put("remote-port", Integer.toString(remotePort));
        int handle = invoke("audioStartRtpOneChannel", values);
        if (handle < 0) throw new IllegalStateException("audioStartRtpOneChannel rejected " + handle);
        return handle;
    }

    static void stopRtp(int handle) {
        if (handle < 0) return;
        try {
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            values.put("handle", Integer.toString(handle));
            int result = invoke("audioStop", values);
            if (result != 0) Log.w(TAG, "audioStop returned " + result + " handle=" + handle);
        } catch (Exception error) {
            Log.w(TAG, "audioStop failed handle=" + handle, error);
        }
    }

    private static int invoke(String action, LinkedHashMap<String, String> values) throws Exception {
        StringBuilder operation = new StringBuilder();
        operation.append("<ns1:").append(action).append('>');
        for (Map.Entry<String, String> value : values.entrySet()) {
            operation.append('<').append(value.getKey()).append('>')
                    .append(xml(value.getValue()))
                    .append("</").append(value.getKey()).append('>');
        }
        operation.append("</ns1:").append(action).append('>');
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:ns1=\"urn:cmm\">"
                + "<SOAP-ENV:Body SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + operation + "</SOAP-ENV:Body></SOAP-ENV:Envelope>\r\n";
        byte[] body = envelope.getBytes(UTF8);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName(LOOPBACK), PORT), 1500);
            socket.setSoTimeout(2500);
            OutputStream output = socket.getOutputStream();
            String headers = "POST / HTTP/1.1\r\nHost: " + LOOPBACK + ':' + PORT
                    + "\r\nUser-Agent: HomePadBridge/1.0\r\n"
                    + "Content-Type: text/xml; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n";
            output.write(headers.getBytes(UTF8));
            output.write(body);
            output.flush();
            String response = readAll(socket.getInputStream());
            if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
                throw new IllegalStateException("CMXMM HTTP response was not 200");
            }
            Matcher matcher = OUT.matcher(response);
            if (!matcher.find()) throw new IllegalStateException("CMXMM response has no out value");
            return Integer.parseInt(matcher.group(1));
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static String readAll(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder value = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) value.append(line).append('\n');
        return value.toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
