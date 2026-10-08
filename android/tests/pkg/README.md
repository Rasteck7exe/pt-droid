# PKG extractor test

Checks `android/app/src/main/java/com/rasteck7/ptdroid/pkg/PkgExtractor.java` against packages built with LibOrbisPkg,
and against the PC installer's own extractor (`installer/Extractor/Program.cs`) on the same packages.

`Gen.cs` builds the fixtures: the US, European and a renamed-files package, one with the newer PFS key derivation, one of
another game, a retail-like one (entry key 3 replaced) and an invalid file. It needs LibOrbisPkg's source with
`tools/patch_liborbis_readers.py` applied, plus a test switch for the newer key derivation:

- `Util/Crypto.cs`: add `public static bool TestNewCrypt;` to `Crypto`;
- `PFS/PFSBuilder.cs`: pass `Crypto.TestNewCrypt` as the third argument of both `Crypto.PfsGenEncKey` calls;
- `PKG/PkgBuilder.cs`: `pkg.Header.pfs_flags = 0x80000000000003CC | (Crypto.TestNewCrypt ? 0x2000000000000000UL : 0UL);`

Then:

    dotnet build -c Release -o out -p:LibOrbisSource=/path/to/LibOrbisPkg
    dotnet out/pkggen.dll /tmp/pt-fixtures
    javac -d classes ../../app/src/main/java/com/rasteck7/ptdroid/pkg/*.java PkgExtractorTest.java
    java -cp classes PkgExtractorTest /tmp/pt-fixtures

Every line should be `PASS`, ending with `ALL PASSED`.
