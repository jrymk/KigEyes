package com.genymobile.scrcpy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/** Trust exactly the receiver certificate installed over the authorized bootstrap. */
public final class PinnedTrust implements X509TrustManager {
    private final String pin;
    public PinnedTrust(String pin) {
        if (!pin.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid receiver pin");
        this.pin=pin;
    }
    public static String fingerprint(X509Certificate cert) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder result=new StringBuilder();
        for (byte value:digest) {
            result.append(Character.forDigit((value>>>4)&15,16));result.append(Character.forDigit(value&15,16));
        }
        return result.toString();
    }
    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    @Override public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {
        throw new CertificateException("Client authentication not used");
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
        try {
            if (chain.length==0) throw new CertificateException("Missing receiver certificate");
            chain[0].checkValidity();
            if (!MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),fingerprint(chain[0]).getBytes(StandardCharsets.US_ASCII))) {
                throw new CertificateException("Receiver certificate mismatch");
            }
        } catch (Exception e) { throw new CertificateException("Receiver certificate rejected"); }
    }
}
