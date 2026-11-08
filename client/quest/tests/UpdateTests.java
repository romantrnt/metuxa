package com.metashare.client;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

public final class UpdateTests {
    private static int checks;
    private static KeyPair key;
    private static String publicKey;
    private static final String PAYLOAD="metuxa-update-v1\nversion=0.3.0\nversion_code=4\nprotocol_min=5\nprotocol_max=5\nandroid_size=123\nandroid_sha256="+"a".repeat(64)+"\nlinux_size=456\nlinux_sha256="+"b".repeat(64)+"\n";
    private static byte[] signed(String payload) throws Exception {
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate()); signer.update(payload.getBytes(StandardCharsets.US_ASCII));
        return (Base64.getEncoder().encodeToString(signer.sign())+"\n"+payload).getBytes(StandardCharsets.US_ASCII);
    }
    private static UpdateManifest verify(byte[] bytes) throws Exception { return UpdateManifest.verifyWithKey(bytes,"0.2.0",3,5,publicKey); }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception { boolean rejected=false; try {action.run();} catch(Exception e) { rejected=true; } if(!rejected) throw new AssertionError("Unsafe manifest accepted"); checks++; }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair(); publicKey=Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        byte[] valid=signed(PAYLOAD); UpdateManifest m=verify(valid);
        if(!m.apkUrl().equals("https://github.com/romantrnt/metuxa/releases/download/v0.3.0/metuxa-0.3.0-arm64-v8a.apk")) throw new AssertionError(); checks++;
        m.requireNotReplayed(4,"0.3.0"); checks++;
        reject(()->m.requireNotReplayed(5,"0.3.0"));
        reject(()->m.requireNotReplayed(4,"0.4.0"));
        if(!UpdateManifest.verifyPolicy(valid,"0.3.0",4,5,publicKey,true).isCurrent("0.3.0",4))throw new AssertionError("Current release not recognized"); checks++;
        reject(()->UpdateManifest.verifyPolicy(valid,"0.3.0",3,5,publicKey,true));
        reject(()->UpdateManifest.verifyPolicy(valid,"0.2.0",4,5,publicKey,true));
        reject(()->UpdateManifest.verifyPolicy(valid,"0.4.0",3,5,publicKey,true));
        reject(()->UpdateManifest.verifyPolicy(valid,"0.3.0",4,6,publicKey,true));
        String envelopeText=new String(valid,StandardCharsets.US_ASCII);
        int separator=envelopeText.indexOf('\n');
        String base64=envelopeText.substring(0,separator);
        reject(()->verify((base64.replace("=","")+envelopeText.substring(separator)).getBytes(StandardCharsets.US_ASCII)));
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int last=base64.indexOf('=')-1;
        String noncanonical=base64.substring(0,last)+alphabet.charAt(alphabet.indexOf(base64.charAt(last))|1)+base64.substring(last+1);
        reject(()->verify((noncanonical+envelopeText.substring(separator)).getBytes(StandardCharsets.US_ASCII)));
        byte[] tampered=valid.clone(); tampered[tampered.length-2]^=1; reject(()->verify(tampered));
        KeyPair other=generator.generateKeyPair(); reject(()->UpdateManifest.verifyWithKey(valid,"0.2.0",3,5,Base64.getEncoder().encodeToString(other.getPublic().getEncoded())));
        reject(()->UpdateManifest.verify(valid,"0.2.0",3,5)); // Test identity must never work with the production pin.
        reject(()->UpdateManifest.verifyWithKey(valid,"0.3.0",4,5,publicKey));
        reject(()->UpdateManifest.verifyWithKey(valid,"0.4.0",5,5,publicKey));
        reject(()->UpdateManifest.verifyWithKey(valid,"0.2.0",4,5,publicKey));
        reject(()->UpdateManifest.verifyWithKey(valid,"0.2.0",3,6,publicKey));
        reject(()->verify(new byte[UpdateManifest.MAX_MANIFEST+1]));
        reject(()->verify(new byte[0]));
        for(String changed:new String[]{PAYLOAD+"\n",PAYLOAD.replace("version_code=4","version_code=04"),PAYLOAD.replace("version=0.3.0","version=0.03.0"),PAYLOAD.replace("android_size=123","android_size=268435457"),PAYLOAD.replace("linux_size=456","linux_size=536870913"),PAYLOAD.replace("android_size=123","android_size=0"),PAYLOAD.replace("version_code=4","version_code=2147483648"),PAYLOAD.replace("protocol_min=5","protocol_min=6"),PAYLOAD.replace("android_sha256=","android_sha256=F"),PAYLOAD.replace("version=0.3.0","version=../../evil"),PAYLOAD.replace("\n","\r\n"),PAYLOAD.replace("linux_size=456","android_size=456")}) reject(()->verify(signed(changed)));
        // Repeated verification leaves no mutable operation state or reusable trust flag.
        for(int i=0;i<100;i++) { verify(valid); reject(()->verify(tampered)); }
        UpdateOperationState state=new UpdateOperationState();
        int first=state.begin(); if(first<0 || state.begin()!=-2) throw new AssertionError("Duplicate operation accepted");
        state.cancel(); if(!state.cancelled())throw new AssertionError("Cancellation lost");
        if(!state.finish(first))throw new AssertionError();
        int second=state.begin(); if(second<=first || state.cancelled())throw new AssertionError("Retry did not reset cancellation");
        if(state.finish(first) || state.current(first) || state.begin()!=-2)throw new AssertionError("Stale callback changed current operation");
        state.close(); if(state.current(second) || state.finish(second) || state.begin()!=-1 || !state.cancelled())throw new AssertionError("Closed lifecycle accepted work");
        for(int i=0;i<100;i++) { UpdateOperationState lifecycle=new UpdateOperationState(); int token=lifecycle.begin(); if(!lifecycle.finish(token) || lifecycle.begin()<0)throw new AssertionError(); lifecycle.close(); checks++; }
        checks+=5;
        System.out.println("Update manifest security tests passed: "+checks);
    }
}
