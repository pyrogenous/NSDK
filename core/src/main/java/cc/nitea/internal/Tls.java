package cc.nitea.internal;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS for old Java runtimes. The Minecraft launcher still runs versions up to 1.16 on Java 8u51, whose certificate
 * store predates Let's Encrypt's roots, so it can't verify the API's certificate. The system store is always tried
 * first; the roots bundled in {@code roots.pem} (ISRG Root X1 and X2) only help when it fails.
 */
final class Tls {
    private static SSLSocketFactory factory;
    private static boolean initialised;

    private Tls() {}

    /** A socket factory trusting the system roots plus the bundled ones, or null to use the default. */
    static synchronized SSLSocketFactory socketFactory() {
        if (initialised) return factory;
        initialised = true;
        try {
            X509TrustManager system = trustManager(null);
            KeyStore roots = bundledStore();
            X509TrustManager bundled = roots != null ? trustManager(roots) : null;
            if (system == null || bundled == null) return null;
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new Combined(system, bundled)}, null);
            factory = context.getSocketFactory();
        } catch (Exception e) {
            factory = null;
        }
        return factory;
    }

    private static KeyStore bundledStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = Tls.class.getResourceAsStream("roots.pem")) {
            if (in == null) return null;
            Collection<? extends java.security.cert.Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            int i = 0;
            for (java.security.cert.Certificate cert : certs) store.setCertificateEntry("nitea-root-" + i++, cert);
        }
        return store;
    }

    // A null store means the system's default roots
    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        return null;
    }

    private static final class Combined implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        Combined(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                bundled.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509Certificate c : system.getAcceptedIssuers()) all.add(c);
            for (X509Certificate c : bundled.getAcceptedIssuers()) all.add(c);
            return all.toArray(new X509Certificate[0]);
        }
    }
}
