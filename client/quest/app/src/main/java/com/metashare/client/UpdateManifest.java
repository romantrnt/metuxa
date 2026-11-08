package com.metashare.client;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Signed release policy, independent of Android and network APIs. */
public final class UpdateManifest {
    public static final int MAX_MANIFEST = 16384;
    public static final long MAX_APK = 256L * 1024 * 1024;
    public static final String REPOSITORY = "https://github.com/romantrnt/metuxa";
    public static final String CERT_SHA256 = "5f5504991278b256e2f890c9b81f6fe5979c978225f24827000bd79c4e6cfdc7";
    private static final String PUBLIC_KEY = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAmkkIZxm8V6MIGMhud4e+Nfjc1I7V+3veW8mLPU+MVy1L2FOUs0i08+7f/ao7S6LcfwWBhRM/CLykmNKGN4jJ+3I/ahmZw8ZjeLR0igqfvK+NLhV0+HXtm1I06TzOrYZKa3tkv7WGm0rm13oIE6czH37w/vm09Zefc5lNrRdbEZGvFONiOzg19zoJCh3p1tVCiIaylVgUzV4totvaWlcSLpyz6sisl7CKc74M6PC3KsyQMQ0gamuY1hSfFvd8WriZcsfCE9av7DQSV0Qmt0DKM9f23X96VgNLSlAALG30xgeiIzS6B/FSibhqmEAGZj/sedwZ0bYBjWMnkgjiykbtBlLyk8II5gXpf8oJvKimaWJZ8pG/NYKns/REWVERE0UytsYPdBMn0qDKA8ZmJTSZXgQQAEciW4CfhlKHlugwkMxA1TgdMOCbfwfbOTzDUPx6jeBM+arEaDrda3y7MvZSJWmd15yr3AGb1KqvCiuiNvMBW/tTtwj81LIhbwuxuX26awtpZs3Qg0D1hivDCooM1a/dmcob9Cfx3iSa/V203rKSCWX02w8SeHcAsaWjA+3ABYxxjVHgBrc3HU1RiTjhxXyLNNRm7OGgmgULAiTvXrQ33UDPxHFJLRihuoZ9HWlAF1e39IegDjrytryqoNSbDPv96t2e1B3rpDVVHluCBwkCAwEAAQ==";
    public final String version, androidSha256, linuxSha256;
    public final int versionCode, protocolMin, protocolMax;
    public final long androidSize, linuxSize;
    private UpdateManifest(String[] lines) {
        version = field(lines[1], "version");
        versionParts(version);
        versionCode = number(field(lines[2], "version_code"));
        protocolMin = number(field(lines[3], "protocol_min"));
        protocolMax = number(field(lines[4], "protocol_max"));
        androidSize = number(field(lines[5], "android_size"));
        androidSha256 = hash(field(lines[6], "android_sha256"));
        linuxSize = number(field(lines[7], "linux_size"));
        linuxSha256 = hash(field(lines[8], "linux_sha256"));
        require(androidSize <= MAX_APK && linuxSize <= 512L * 1024 * 1024, "Release exceeds size limit");
        require(protocolMin <= protocolMax, "Invalid protocol range");
    }
    public static UpdateManifest verify(byte[] envelope, String currentVersion, int currentCode, int protocol) throws Exception {
        return verifyWithKey(envelope, currentVersion, currentCode, protocol, PUBLIC_KEY);
    }
    public static UpdateManifest verifyForCheck(byte[] envelope, String currentVersion, int currentCode, int protocol) throws Exception {
        return verifyPolicy(envelope,currentVersion,currentCode,protocol,PUBLIC_KEY,true);
    }
    // Package-private key injection exists only for deterministic local security tests.
    static UpdateManifest verifyWithKey(byte[] envelope, String currentVersion, int currentCode, int protocol, String key) throws Exception {
        return verifyPolicy(envelope,currentVersion,currentCode,protocol,key,false);
    }
    static UpdateManifest verifyPolicy(byte[] envelope, String currentVersion, int currentCode, int protocol, String key, boolean allowCurrent) throws Exception {
        require(envelope.length > 0 && envelope.length <= MAX_MANIFEST, "Invalid manifest size");
        int newline = -1;
        for (int i=0; i<envelope.length; i++) { if (envelope[i]=='\n') { newline=i; break; } }
        require(newline > 0 && newline <= 1024, "Invalid signature envelope");
        String encoded = new String(envelope, 0, newline, StandardCharsets.US_ASCII);
        require(encoded.matches("[A-Za-z0-9+/]+={0,2}"), "Invalid signature encoding");
        byte[] signature=Base64.getDecoder().decode(encoded);
        require(Base64.getEncoder().encodeToString(signature).equals(encoded), "Noncanonical signature encoding");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key))));
        verifier.update(envelope, newline+1, envelope.length-newline-1);
        require(verifier.verify(signature), "Release signature verification failed");
        // Parse only authenticated bytes, rejecting non-ASCII, CR, duplicate/extra fields.
        for (int i=newline+1; i<envelope.length; i++) require(envelope[i]=='\n' || (envelope[i]>=32 && envelope[i]<=126), "Invalid manifest character");
        String[] lines = new String(envelope,newline+1,envelope.length-newline-1,StandardCharsets.US_ASCII).split("\n",-1);
        require(lines.length == 10 && lines[0].equals("metuxa-update-v1") && lines[9].isEmpty(), "Unsupported manifest format");
        UpdateManifest m = new UpdateManifest(lines);
        require(protocol >= m.protocolMin && protocol <= m.protocolMax, "Release protocol is incompatible; update both devices manually");
        boolean newer=compareVersions(m.version,currentVersion)>0 && m.versionCode>currentCode;
        require(newer || (allowCurrent && m.isCurrent(currentVersion,currentCode)), "Signed release is older or has inconsistent version numbers; update refused");
        return m;
    }
    public boolean isCurrent(String version,int code) { return this.version.equals(version) && versionCode==code; }
    public void requireNotReplayed(int highestCode,String highestVersion) { require(versionCode>=highestCode && compareVersions(version,highestVersion)>=0, "An older signed release was replayed; update refused"); }
    public String apkUrl() { return REPOSITORY+"/releases/download/v"+version+"/metuxa-"+version+"-arm64-v8a.apk"; }
    private static String field(String line,String name) { require(line.startsWith(name+"="),"Invalid manifest field"); return line.substring(name.length()+1); }
    private static int number(String s) { require(s.matches("[1-9][0-9]{0,9}"),"Invalid manifest number"); long n=Long.parseLong(s); require(n<=Integer.MAX_VALUE,"Number exceeds limit"); return (int)n; }
    private static String hash(String s) { require(s.matches("[0-9a-f]{64}"),"Invalid release digest"); return s; }
    private static int[] versionParts(String v) {
        require(v.matches("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})"),"Invalid release version");
        String[] p=v.split("\\."); return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};
    }
    public static int compareVersions(String a,String b) { int[] x=versionParts(a),y=versionParts(b); for(int i=0;i<3;i++) { int c=Integer.compare(x[i],y[i]); if(c!=0)return c; } return 0; }
    private static void require(boolean ok,String error) { if(!ok)throw new IllegalArgumentException(error); }
}
