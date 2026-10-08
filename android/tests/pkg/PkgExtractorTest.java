import com.rasteck7.ptdroid.pkg.PkgExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs PT Droid's PkgExtractor on the fixtures that pkggen built with LibOrbisPkg (manifest.txt) and checks the bytes. */
public class PkgExtractorTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 20];
            for (int n; (n = in.read(b)) > 0;) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02X", x));
        return s.toString();
    }

    static PkgExtractor.Source source(RandomAccessFile f) {
        return new PkgExtractor.Source() {
            public long size() throws IOException { return f.length(); }
            public void read(long pos, byte[] b, int off, int len) throws IOException {
                f.seek(pos);
                f.readFully(b, off, len);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        List<String> lines = Files.readAllLines(new File(root, "manifest.txt").toPath());
        for (String line : lines) {
            String[] parts = line.trim().split(" ");
            if (parts.length < 2) continue;
            String pkg = parts[0], expect = parts[1];
            Map<String, String> hashes = new HashMap<>();
            for (int i = 2; i < parts.length; ++i) {
                String[] kv = parts[i].split("=");
                hashes.put(kv[0], kv[1]);
            }
            File out = new File(root, pkg.replace(".pkg", ".java"));
            try (RandomAccessFile f = new RandomAccessFile(new File(root, pkg), "r")) {
                PkgExtractor.Source src = source(f);
                String outcome;
                try {
                    if (!PkgExtractor.isPkg(src)) throw new IOException("not a PS4 package");
                    long start = System.nanoTime();
                    PkgExtractor.Contents c = PkgExtractor.open(src);
                    PkgExtractor.extract(c, out, null);
                    double ms = (System.nanoTime() - start) / 1e6;
                    outcome = "ok";
                    for (String name : PkgExtractor.GAME_FILES) {
                        String got = sha256(new File(out, name));
                        check(got.equals(hashes.get(name)), pkg + " " + name + " bytes match");
                        File reference = new File(root, pkg.replace(".pkg", ".reference") + "/" + name);
                        if (reference.exists()) check(got.equals(sha256(reference)), pkg + " " + name + " matches the PC extractor");
                    }
                    check(!new File(out, "chunk1.psarc.part").exists(), pkg + " no partial file left");
                    System.out.printf("     %s: %s, %d notes %s, %.0f ms%n", pkg, c.titleId, c.notes.size(), c.notes, ms);
                } catch (PkgExtractor.RetailException e) {
                    outcome = "retail";
                } catch (PkgExtractor.NotPtException e) {
                    outcome = "notpt";
                } catch (IOException e) {
                    outcome = "invalid";
                    if (!expect.equals("invalid")) e.printStackTrace();
                }
                check(outcome.equals(expect), pkg + " -> " + outcome + " (expected " + expect + ")");
            }
        }
        // a truncated package (the download stopped half way) must not pass as complete
        File us = new File(root, "us.pkg");
        File cut = new File(root, "truncated.pkg");
        byte[] all = Files.readAllBytes(us.toPath());
        Files.write(cut.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
        try (RandomAccessFile f = new RandomAccessFile(cut, "r")) {
            String outcome;
            try {
                PkgExtractor.extract(PkgExtractor.open(source(f)), new File(root, "truncated.java"), null);
                outcome = "ok";
            } catch (IOException e) {
                outcome = e instanceof PkgExtractor.RetailException ? "retail" : "error: " + e.getMessage();
            }
            check(outcome.startsWith("error"), "truncated.pkg refused (" + outcome + ")");
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
