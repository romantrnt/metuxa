import com.metashare.client.UpdateManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Offline final-artifact verification using the unchanged production trust pin. */
public final class VerifyManifest {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 && args.length!=5) throw new IllegalArgumentException("Usage: VerifyManifest MANIFEST APK APPIMAGE [CURRENT_VERSION CURRENT_CODE]");
        Path document=Path.of(args[0]),apk=Path.of(args[1]),linux=Path.of(args[2]);
        if(Files.size(document)>UpdateManifest.MAX_MANIFEST)throw new SecurityException("Manifest exceeds limit");
        String current=args.length==5?args[3]:"0.2.0";
        int code=args.length==5?Integer.parseInt(args[4]):3;
        UpdateManifest manifest=UpdateManifest.verify(Files.readAllBytes(document),current,code,5);
        check(apk,"metuxa-"+manifest.version+"-arm64-v8a.apk",manifest.androidSize,manifest.androidSha256);
        check(linux,"metuxa-"+manifest.version+"-x86_64.AppImage",manifest.linuxSize,manifest.linuxSha256);
        // The same signed current announcement must pass check-only verification.
        UpdateManifest exact=UpdateManifest.verifyForCheck(Files.readAllBytes(document),manifest.version,manifest.versionCode,5);
        if(!exact.isCurrent(manifest.version,manifest.versionCode))throw new SecurityException("Current-release check failed");
        System.out.println("Production signature, protocol, upgrade policy and both artifact hashes verified for metuxa "+manifest.version+" (code "+manifest.versionCode+").");
    }
    private static void check(Path file,String name,long size,String hash) throws Exception {
        if(!file.getFileName().toString().equals(name) || Files.size(file)!=size)throw new SecurityException("Release artifact name or size mismatch");
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int n;
        try(var input=Files.newInputStream(file)) { while((n=input.read(buffer))!=-1)digest.update(buffer,0,n); }
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new SecurityException("Release artifact digest mismatch");
    }
}
