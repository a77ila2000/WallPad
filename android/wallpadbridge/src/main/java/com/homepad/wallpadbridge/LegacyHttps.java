package com.homepad.wallpadbridge;

import android.content.Context;

import org.conscrypt.Conscrypt;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Shared Android 4.2 TLS setup for all wallpad-to-server traffic. */
final class LegacyHttps {
    private static volatile SSLSocketFactory ready;

    private LegacyHttps() { }

    static HttpsURLConnection open(Context context, String url, String method,
                                    int connectTimeout, int readTimeout) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setSSLSocketFactory(socketFactory(context));
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestMethod(method);
        return connection;
    }

    private static SSLSocketFactory socketFactory(Context context) throws Exception {
        SSLSocketFactory value = ready;
        if (value != null) return value;
        synchronized (LegacyHttps.class) {
            if (ready == null) ready = build(context);
            return ready;
        }
    }

    private static SSLSocketFactory build(Context context) throws Exception {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        InputStream input = context.getResources().openRawResource(R.raw.isrgrootx1);
        Certificate certificate = factory.generateCertificate(input);
        input.close();
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("isrg-root-x1", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS", "Conscrypt");
        ssl.init(null, managers.getTrustManagers(), null);
        return new Http11SocketFactory(ssl.getSocketFactory());
    }

    /** The legacy URLConnection cannot consume an HTTP/2 response. */
    private static final class Http11SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Http11SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private Socket prepare(Socket value) {
            if (value instanceof SSLSocket && Conscrypt.isConscrypt((SSLSocket) value)) {
                Conscrypt.setApplicationProtocols(
                        (SSLSocket) value, new String[] {"http/1.1"});
            }
            return value;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket(Socket socket, String host, int port,
                                              boolean close) throws IOException {
            return prepare(delegate.createSocket(socket, host, port, close));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return prepare(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port,
                                              InetAddress localHost, int localPort)
                throws IOException {
            return prepare(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return prepare(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(InetAddress address, int port,
                                              InetAddress localAddress, int localPort)
                throws IOException {
            return prepare(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
