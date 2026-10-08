// Builds synthetic fake PKGs with LibOrbisPkg for testing PT Droid's Java extractor, and extracts each with the PC
// installer's own extractor (installer/Extractor/Program.cs) as the reference.
using System.Security.Cryptography;
using System.Text;
using LibOrbisPkg.PFS;
using LibOrbisPkg.PKG;
using LibOrbisPkg.SFO;
using LibOrbisPkg.GP4;
using LibOrbisPkg.Util;
using PT.PkgExtract;

public static class Gen {
    static byte[] Data(string name, int size, bool pt) {
        var rng = new Random(name.GetHashCode() ^ size);
        var b = new byte[size];
        // half random, half compressible text, so PFSC blocks are both stored and deflated
        for (int i = 0; i < size; ++i) b[i] = (i / 65536) % 2 == 0 ? (byte)rng.Next(256) : (byte)("hallway_loop_"[i % 13]);
        if (name.EndsWith(".psarc")) Encoding.ASCII.GetBytes("PSAR").CopyTo(b, 0);
        if (name.EndsWith(".qar")) { b[size - 0x24 + 0x16] = (byte)'a'; b[size - 0x24 + 0x17] = (byte)'q'; }
        if (name.Contains("pathid")) {
            var text = Encoding.ASCII.GetBytes(pt ? "/Assets/sh/level/pt14_hallway/pt14_loop_00.fpk " : "/Assets/other/level/x.fpk ");
            for (int i = 0; i + text.Length <= size; i += text.Length) text.CopyTo(b, i);
        }
        return b;
    }

    static string Build(string root, string file, string contentId, Func<string, string> place, bool pt, Dictionary<string, byte[]> data) {
        var tree = new FSDir();
        var sys = new FSDir { name = "sce_sys", Parent = tree }; tree.Dirs.Add(sys);
        var sfo = ParamSfo.DefaultAC; sys.Files.Add(new FSFile(s => sfo.Write(s), "param.sfo", sfo.FileSize) { Parent = sys });
        foreach (var name in Extraction.Required) {
            var bytes = data[name];
            string where = place(name); FSDir dir = tree; string leaf = where;
            if (where.Contains('/')) {
                string dn = where.Split('/')[0];
                dir = tree.Dirs.FirstOrDefault(d => d.name == dn) ?? new FSDir { name = dn, Parent = tree };
                if (!tree.Dirs.Contains(dir)) tree.Dirs.Add(dir);
                leaf = where.Split('/')[1];
            }
            dir.Files.Add(new FSFile(s => s.Write(bytes), leaf, bytes.Length) { Parent = dir });
        }
        string pkg = Path.Combine(root, file);
        new PkgBuilder(new PkgProperties { ContentId = contentId, Passcode = new string('0', 32), EntitlementKey = new string('0', 32), RootDir = tree,
            VolumeType = VolumeType.pkg_ps4_ac_data, TimeStamp = new(2026, 10, 2) }).Write(pkg, _ => { });
        return pkg;
    }

    public static int Main(string[] args) {
        string root = Path.GetFullPath(args[0]);
        Directory.CreateDirectory(root);
        var sizes = new Dictionary<string, int> { ["chunk1.psarc"] = 5 * 1024 * 1024 + 4321, ["texture.qar"] = 3 * 1024 * 1024 + 77, ["pathid_list_ps4.bin"] = 200 * 1024 + 5 };
        Dictionary<string, byte[]> Set(bool pt) => sizes.ToDictionary(kv => kv.Key, kv => Data(kv.Key, kv.Value, pt));
        var pt = Set(true);
        var manifest = new StringBuilder();
        void Record(string pkg, string expect, Dictionary<string, byte[]> d) {
            manifest.Append(Path.GetFileName(pkg)).Append(' ').Append(expect);
            if (d != null) foreach (var n in Extraction.Required) manifest.Append(' ').Append(n).Append('=').Append(Convert.ToHexString(SHA256.HashData(d[n])));
            manifest.Append('\n');
            // the PC installer's extractor on the same package, as the reference
            string outDir = Path.Combine(root, Path.GetFileNameWithoutExtension(pkg) + ".reference");
            try { Extraction.Run(pkg, outDir, _ => { }); Console.WriteLine($"{Path.GetFileName(pkg)}: reference extracted"); }
            catch (Exception e) { Console.WriteLine($"{Path.GetFileName(pkg)}: reference refused: {e.Message.Split('.')[0]}"); }
        }
        Record(Build(root, "us.pkg", "UP0000-CUSA01127_00-0000000000000000", n => n, true, pt), "ok", pt);
        Record(Build(root, "moved.pkg", "UP0000-CUSA01127_00-0000000000000000", n => "data/v2_" + n, true, pt), "ok", pt);
        Record(Build(root, "eu.pkg", "EP0101-CUSA01114_00-0000000000000000", n => n, true, pt), "ok", pt);
        var other = Set(false);
        Record(Build(root, "other.pkg", "UP9999-CUSA99998_00-0000000000000000", n => n, false, other), "notpt", null);
        Crypto.TestNewCrypt = true;
        Record(Build(root, "newcrypt.pkg", "UP0000-CUSA01127_00-0000000000000000", n => n, true, pt), "ok", pt);
        Crypto.TestNewCrypt = false;
        // a retail-like package: entry key 3 is not the fake keyset's
        string us = Path.Combine(root, "us.pkg");
        var parsed = new PkgReader(File.OpenRead(us)).ReadPkg();
        var keys = parsed.Metas.Metas.Single(m => m.id == EntryId.ENTRY_KEYS);
        string retail = Path.Combine(root, "retail.pkg"); File.Copy(us, retail, true);
        using (var f = new FileStream(retail, FileMode.Open, FileAccess.ReadWrite)) {
            f.Position = keys.DataOffset + 0x20 + 7 * 0x20 + 3 * 0x100; var garbage = new byte[0x100]; new Random(1).NextBytes(garbage); f.Write(garbage);
        }
        Record(retail, "retail", null);
        File.WriteAllText(Path.Combine(root, "invalid.pkg"), "not a package at all, just text");
        manifest.Append("invalid.pkg invalid\n");
        File.WriteAllText(Path.Combine(root, "manifest.txt"), manifest.ToString());
        Console.WriteLine("fixtures in " + root);
        return 0;
    }
}
