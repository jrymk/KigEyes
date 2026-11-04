package com.genymobile.scrcpy;

import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;

public final class DirectSecurityTest {
    public static void main(String[] args) throws Exception {
        X509Certificate cert;
        try (FileInputStream input=new FileInputStream(args[0])) {
            cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        PinnedTrust trust=new PinnedTrust(PinnedTrust.fingerprint(cert));
        trust.checkServerTrusted(new X509Certificate[]{cert},"RSA");
        try {
            new PinnedTrust(new String(new char[64]).replace('\0','0')).checkServerTrusted(new X509Certificate[]{cert},"RSA");
            throw new AssertionError("Unpinned certificate accepted");
        } catch (CertificateException expected) { }
        try {
            trust.checkServerTrusted(new X509Certificate[0],"RSA");
            throw new AssertionError("Missing certificate accepted");
        } catch (CertificateException expected) { }
        try {
            trust.checkClientTrusted(new X509Certificate[]{cert},"RSA");
            throw new AssertionError("Client certificate used as receiver");
        } catch (CertificateException expected) { }
        for (String action:new String[]{"photo_camera","video_camera","shield_setup","shield_lock","shield_status",
                "shield_unlock","shield_permit","shield_restore","shield_renew"}) {
            if (!DirectActions.arguments(action)[0].equals("/system/bin/am")) throw new AssertionError("Unexpected executable");
        }
        for (String action:new String[]{"shell","exec","am","photo_camera;id","shield_lock xyz",""}) {
            try { DirectActions.arguments(action);throw new AssertionError("Unknown action allowed"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("DirectSecurity: certificate pin, missing/wrong identity and restricted actions passed");
    }
}
