package com.asiafactor.app;

import android.content.Context;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Offline, device-bound license verification. The vendor private key must never be shipped in the APK. */
public final class LicenseManager {
    public static final String APP_ID = "com.asiafactor.app";
    private static final String KEY_ALIAS = "asiafactor_device_identity_v1";
    private static final String LICENSE_FILE = "asiafactor-license.json";
    // Public verification key only. The matching private key belongs to the owner and stays offline.
    private static final String VENDOR_PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzyQo1Qrzr+p2SXmi9lrfQAhqOLgESuxAfLme0QMXwpc2+NXv0HNVkwLpivozpf0M0Y+pO6RUURrktY262kJnYEnO1DG0LrNbESRhlEu/+CUVMovBPgR2SRi9GVy973fgppX4pJAn8mOwalyWCM6/+xz/F5n1DvA8IMAXMNNXyW/9vrUO2OlJnHZrF7Beutg3DhjmWBEqlphgV0azCLd5S4EfkveU5l0l6JKSDbhWy7dR32rjnDLHmXdru6OxpBWvawtddqpmIuYQEN0+GYUm9WPCQ3+MoELo9BdUl8iCU3TlCkvEQ0A0xJE81ilx1YnYD6bboX/L6JpxzHsr1Iit1wIDAQAB";

    private LicenseManager() {}

    private static KeyPair getOrCreateDeviceKeyPair() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            java.security.PrivateKey privateKey = (java.security.PrivateKey) ks.getKey(KEY_ALIAS, null);
            java.security.cert.Certificate cert = ks.getCertificate(KEY_ALIAS);
            if (privateKey != null && cert != null) {
                return new KeyPair(cert.getPublicKey(), privateKey);
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        return generator.generateKeyPair();
    }

    /**
     * Uses Android's app-scoped ANDROID_ID as a reinstall-stable device binding.
     * This normally survives uninstall/reinstall for the same app-signing key, user,
     * and device, but can change after factory reset or signing-key/user changes.
     * It is weaker than a hardware-backed key and is an offline licensing trade-off.
     */
    private static String getStableDeviceHash(Context context) throws Exception {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            throw new IllegalStateException("شناسه پایدار دستگاه در دسترس نیست.");
        }
        return sha256Hex(androidId.getBytes("UTF-8"));
    }

    public static String createActivationRequest(Context context) throws Exception {
        KeyPair pair = getOrCreateDeviceKeyPair();
        String publicKey = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
        String hash = getStableDeviceHash(context);
        JSONObject request = new JSONObject();
        request.put("format", "ASIAFACTOR-ACTIVATION-REQUEST-1");
        request.put("appId", APP_ID);
        request.put("devicePublicKey", publicKey);
        request.put("deviceKeyHash", hash);
        return request.toString(2);
    }

    public static File getLicenseFile(Context context) {
        return new File(context.getFilesDir(), LICENSE_FILE);
    }

    public static boolean isLicenseValid(Context context) {
        File file = getLicenseFile(context);
        if (!file.isFile()) return false;
        try {
            byte[] data = readAll(file);
            JSONObject envelope = new JSONObject(new String(data, "UTF-8"));
            String payloadB64 = envelope.getString("payloadBase64");
            String signatureB64 = envelope.getString("signatureBase64");
            byte[] payload = Base64.decode(payloadB64, Base64.DEFAULT);
            byte[] signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT);

            byte[] vendorKeyBytes = Base64.decode(VENDOR_PUBLIC_KEY_B64, Base64.DEFAULT);
            java.security.PublicKey vendorKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(vendorKeyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(vendorKey);
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) return false;

            JSONObject claims = new JSONObject(new String(payload, "UTF-8"));
            if (!"ASIAFACTOR-LICENSE-1".equals(claims.getString("format"))) return false;
            if (!APP_ID.equals(claims.getString("appId"))) return false;
            String claimedHash = claims.getString("deviceKeyHash");
            String currentHash = getStableDeviceHash(context);
            return currentHash.equalsIgnoreCase(claimedHash);
        } catch (Exception e) {
            return false;
        }
    }

    public static String validateLicenseJson(Context context, String licenseJson) {
        try {
            JSONObject envelope = new JSONObject(licenseJson);
            byte[] payload = Base64.decode(envelope.getString("payloadBase64"), Base64.DEFAULT);
            byte[] signatureBytes = Base64.decode(envelope.getString("signatureBase64"), Base64.DEFAULT);
            java.security.PublicKey vendorKey = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(VENDOR_PUBLIC_KEY_B64, Base64.DEFAULT)));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(vendorKey);
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) return "امضای فایل مجوز معتبر نیست.";
            JSONObject claims = new JSONObject(new String(payload, "UTF-8"));
            if (!"ASIAFACTOR-LICENSE-1".equals(claims.getString("format"))) return "فرمت مجوز پشتیبانی نمی‌شود.";
            if (!APP_ID.equals(claims.getString("appId"))) return "این مجوز برای برنامه دیگری صادر شده است.";
            String currentHash = getStableDeviceHash(context);
            if (!currentHash.equalsIgnoreCase(claims.getString("deviceKeyHash"))) {
                return "این مجوز متعلق به این گوشی نیست.";
            }
            return null;
        } catch (Exception e) {
            return "فایل مجوز خراب یا نامعتبر است.";
        }
    }

    public static void installLicense(Context context, String licenseJson) throws Exception {
        String error = validateLicenseJson(context, licenseJson);
        if (error != null) throw new IllegalArgumentException(error);
        try (FileOutputStreamCompat out = new FileOutputStreamCompat(getLicenseFile(context))) {
            out.write(licenseJson.getBytes("UTF-8"));
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    // Tiny wrapper keeps this class compatible with the project's Java 8 language level.
    private static final class FileOutputStreamCompat implements java.io.Closeable {
        private final java.io.FileOutputStream stream;
        FileOutputStreamCompat(File file) throws Exception { stream = new java.io.FileOutputStream(file); }
        void write(byte[] bytes) throws Exception { stream.write(bytes); stream.flush(); }
        @Override public void close() throws java.io.IOException { stream.close(); }
    }
}
