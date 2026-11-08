package com.metashare.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

/** PAIR1 fixed-width transcript hashes. Domains include one trailing NUL. */
final class PairingCode {
    private PairingCode() {}
    static String hex(byte[] bytes) {
        char[] out=new char[bytes.length*2];
        final char[] digits="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++) { out[i*2]=digits[(bytes[i]&255)>>>4]; out[i*2+1]=digits[bytes[i]&15]; }
        return new String(out);
    }
    private static byte[] hash(String domain,byte[]... fields) throws GeneralSecurityException {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        hash.update(domain.getBytes(StandardCharsets.US_ASCII)); hash.update((byte)0);
        for(byte[] field:fields) hash.update(field);
        return hash.digest();
    }
    static String commitment(String fingerprint,String nonce) throws GeneralSecurityException {
        return hex(hash("metuxa-pair1-client-commit",SecureTransport.unhex(fingerprint,32),SecureTransport.unhex(nonce,32)));
    }
    static String comparison(String fingerprint,String clientNonce,String serverNonce,String id) throws GeneralSecurityException {
        byte[] digest=hash("metuxa-pair1-compare",SecureTransport.unhex(fingerprint,32),SecureTransport.unhex(clientNonce,32),
                           SecureTransport.unhex(serverNonce,32),SecureTransport.unhex(id,16));
        long value=((digest[0]&255L)<<24)|((digest[1]&255L)<<16)|((digest[2]&255L)<<8)|(digest[3]&255L);
        String digits=String.format(Locale.ROOT,"%010d",value);
        return digits.substring(0,5)+" "+digits.substring(5);
    }
}
