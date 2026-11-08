import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Uses the existing APK identity locally; never exports or logs private material. */
public final class SignManifest {
    private static final String CERT="5f5504991278b256e2f890c9b81f6fe5979c978225f24827000bd79c4e6cfdc7";
    public static void main(String[] args) throws Exception {
        if(args.length!=8) throw new IllegalArgumentException("Usage: SignManifest.java VERSION CODE PROTOCOL_MIN PROTOCOL_MAX APK APPIMAGE OUTPUT SIGNING_DIR");
        String version=args[0];
        if(!version.matches("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")) throw new IllegalArgumentException("Invalid version");
        int code=number(args[1]),min=number(args[2]),max=number(args[3]);
        if(min>max) throw new IllegalArgumentException("Invalid protocol range");
        Path apk=Path.of(args[4]),linux=Path.of(args[5]),out=Path.of(args[6]),dir=Path.of(args[7]);
        if(!apk.getFileName().toString().equals("metuxa-"+version+"-arm64-v8a.apk") || !linux.getFileName().toString().equals("metuxa-"+version+"-x86_64.AppImage")) throw new IllegalArgumentException("Release asset names must match version and architecture");
        long as=Files.size(apk),ls=Files.size(linux);
        if(as<=0 || as>256L*1024*1024 || ls<=0 || ls>512L*1024*1024) throw new IllegalArgumentException("Invalid asset sizes");
        String payload="metuxa-update-v1\nversion="+version+"\nversion_code="+code+"\nprotocol_min="+min+"\nprotocol_max="+max+"\nandroid_size="+as+"\nandroid_sha256="+hash(apk)+"\nlinux_size="+ls+"\nlinux_sha256="+hash(linux)+"\n";
        char[] password=Files.readString(dir.resolve("keystore.password"),StandardCharsets.UTF_8).stripTrailing().toCharArray();
        try {
            KeyStore store=KeyStore.getInstance("PKCS12");
            try(var input=Files.newInputStream(dir.resolve("metuxa-release.p12"))) { store.load(input,password); }
            var cert=store.getCertificate("metuxa-release");
            if(cert==null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())).equals(CERT)) throw new SecurityException("Unexpected release signing identity");
            Signature signer=Signature.getInstance("SHA256withRSA");
            signer.initSign((PrivateKey)store.getKey("metuxa-release",password));
            signer.update(payload.getBytes(StandardCharsets.US_ASCII));
            byte[] signature=signer.sign();
            Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(cert); verifier.update(payload.getBytes(StandardCharsets.US_ASCII));
            if(!verifier.verify(signature)) throw new SecurityException("Signature self-check failed");
            Files.writeString(out,Base64.getEncoder().encodeToString(signature)+"\n"+payload,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            System.out.println("Signed release manifest: "+out.getFileName());
        } finally { Arrays.fill(password,'\0'); }
    }
    private static int number(String text) { if(!text.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid positive number"); return Integer.parseInt(text); }
    private static String hash(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int n;
        try(var input=Files.newInputStream(path)) { while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
