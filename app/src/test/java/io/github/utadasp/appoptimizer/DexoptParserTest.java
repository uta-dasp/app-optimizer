package io.github.utadasp.appoptimizer;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class DexoptParserTest {
    @Test public void fullMeansAllReportedArtifactsAreFull() {
        Map<String,DexoptParser.Status> s=DexoptParser.parse("Dexopt state:\n  [com.demo.app]\n path: base.apk\n arm: [status=speed] [reason=cmdline]\n path: split.apk\n arm64: [status=speed] [reason=cmdline]\n");
        assertEquals("Full",s.get("com.demo.app").label);
        assertTrue(s.get("com.demo.app").details.contains("cmdline"));
    }
    @Test public void differentSplitOrArchitectureIsMixed() {
        assertEquals("Mixed",DexoptParser.parse(" [com.demo.app]\n arm: [status=speed]\n arm64: [status=verify]\n").get("com.demo.app").label);
    }
    @Test public void unknownArtifactDoesNotBecomeFull() {
        assertEquals("Mixed",DexoptParser.parse(" [com.demo.app]\n [status=speed]\n [status=unknown]\n").get("com.demo.app").label);
    }
    @Test public void packageWithNoRecordsIsUnknown() {
        assertEquals("Unknown",DexoptParser.parse(" [com.demo.app]\n [com.other.app]\n [status=speed]").get("com.demo.app").label);
    }
    @Test public void profileIsOptimizedButNotFull() {
        assertEquals("Profile guided",DexoptParser.parse(" [com.demo.app]\n [status=speed-profile]").get("com.demo.app").label);
    }
    @Test public void successCommandCannotOverrideVerifiedOnly() {
        assertEquals("Verified only",DexoptParser.parse(" [io.freemytv.smartlab]\n arm: [status=verify] [reason=cmdline]").get("io.freemytv.smartlab").label);
    }
    @Test public void nativeOrNoDexDoesNotPretendOptimization() {
        assertEquals("Unknown",DexoptParser.parse(" [com.demo.native]\n").get("com.demo.native").label);
        assertEquals("Not compiled",DexoptParser.parse(" [com.demo.web]\n [status=run-from-apk]").get("com.demo.web").label);
    }
    @Test public void unreadableNewFormatIsUnknown() {assertTrue(DexoptParser.parse("Permission Denial: inaccessible").isEmpty());}
    @Test public void packagesValidateAndNeverExecuteShellText() {
        assertTrue(DexoptParser.validPackage("com.nuvio.tv.test"));
        for(String input:new String[]{"x; reboot","com.app\nreboot","$(id)","com.app'","-a","android",null}) assertFalse(DexoptParser.validPackage(input));
        assertEquals("pm compile -m speed -f com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",false,false));
        assertEquals("pm compile -m verify -f com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",true,false));
        assertEquals("pm compile -m speed-profile -f com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",false,true));
    }
    @Test public void modernCommandsExplicitlyLimitCompilationToPrimaryDex() {
        assertEquals("pm compile -m speed -f --primary-dex com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",false,false,true));
        assertEquals("pm compile -m verify -f --primary-dex com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",true,false,true));
        assertEquals("pm compile -m speed-profile -f --primary-dex com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",false,true,true));
    }
    @Test public void androidSevenReversalUsesInterpretOnlyExplicitly() {
        assertEquals("pm compile -m interpret-only -f com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",true,false,false,true));
        assertEquals("pm compile -m speed -f com.nuvio.tv",DexoptParser.compileCommand("com.nuvio.tv",false,false,false,true));
        assertEquals("Interpreted",DexoptParser.parse(" [com.demo.app]\n arm: [status=interpret-only]\n").get("com.demo.app").label);
    }
    @Test public void modernCapabilityProbeRequiresAdvertisedFlag() {
        assertTrue(DexoptParser.supportsPrimaryDex("  compile [-m MODE] [-f] PACKAGE\n    --primary-dex: only optimizes primary dex files.\n"));
        assertFalse(DexoptParser.supportsPrimaryDex("  compile [-m MODE] [-f] PACKAGE\n    --secondary-dex: secondary only.\n"));
        assertFalse(DexoptParser.supportsPrimaryDex("Error: unknown option --primary-dex\n"));
    }
    @Test public void modernArtDumpPrimaryArm64MatchesDocumentedFormat() {
        String fixture="[com.demo.app]\n  path: /data/app/example/base.apk\n    arm64: [status=speed] [reason=cmdline] [primary-abi]\n      [location is /data/app/example/oat/arm64/base.odex]\n  known secondary dex files:\n    /data/user/0/com.demo.app/cache/secondary.jar\n      arm64: [status=verify] [reason=unknown]\n";
        DexoptParser.Status s=DexoptParser.parse(fixture,true).get("com.demo.app");
        assertEquals("Full",s.label);assertTrue(s.details.startsWith("Primary dex filters:"));
        assertEquals("Mixed",DexoptParser.parse(fixture).get("com.demo.app").label);
    }
    @Test public void modernSplitArtifactMixIsPreserved() {
        String fixture="[com.demo.app]\n  path: /data/app/example/base.apk\n    arm64: [status=speed] [reason=cmdline] [primary-abi]\n  path: /data/app/example/split_feature.apk\n    arm64: [status=verify] [reason=install] [primary-abi]\n";
        assertEquals("Mixed",DexoptParser.parse(fixture,true).get("com.demo.app").label);
    }
    @Test public void modernSecondaryScopeResetsAtNextPackage() {
        String fixture="[com.demo.one]\n  known secondary dex files:\n    arm64: [status=verify]\n[com.demo.two]\n  path: /data/app/second/base.apk\n    arm64: [status=speed]\n";
        assertEquals("Unknown",DexoptParser.parse(fixture,true).get("com.demo.one").label);
        assertEquals("Full",DexoptParser.parse(fixture,true).get("com.demo.two").label);
    }
    @Test public void oldAndroidSevenCandidateArtifactIsNotAssumedUsable() {
        String fixture=" [com.demo.app]\n   path: /data/app/com.demo.app/base.apk\n   status: /data/app/com.demo.app/oat/arm/base.odex [compilation_filter=speed, status=kOatDexOutOfDate]\n";
        assertEquals("Unknown",DexoptParser.parse(fixture).get("com.demo.app").label);
    }
    @Test(expected=IllegalArgumentException.class) public void maliciousPackageRejected() {DexoptParser.compileCommand("com.app;id",false,false);}
    @Test public void storageReadsAvailableNotUsed() {
        DexoptParser.Space s=DexoptParser.space("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-11  23068672 9000000 14068672 40% /data\n");
        assertEquals(14068672L*1024,s.freeBytes);assertEquals(23068672L*1024,s.totalBytes);
    }
    @Test(expected=IllegalArgumentException.class) public void malformedStorageRejected() {DexoptParser.space("/dev/foo 1000 1000 2000 1% /data");}
    @Test public void tvBindMountAliasStillMeasuresDataFilesystem() {
        DexoptParser.Space s=DexoptParser.space("Filesystem       1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-12  20472960 6442104  13883400  32% /storage/emulated/0/Android/obb\n");
        assertEquals(20472960L*1024,s.totalBytes);assertEquals(13883400L*1024,s.freeBytes);
    }
    @Test(expected=IllegalArgumentException.class) public void ambiguousFilesystemRowsRejected() {
        DexoptParser.space("/dev/foo 1000 600 400 60% /data\n/dev/bar 2000 1000 1000 50% /storage/emulated/0/Android/obb\n");
    }
    @Test(expected=IllegalArgumentException.class) public void malformedNumericStorageRowRejected() {
        DexoptParser.space("/dev/foo 1000 unknown 400 60% /storage/emulated/0/Android/obb\n");
    }
    @Test public void installerDetectionDoesNotIncludeMalformedNames() {
        Map<String,String> p=DexoptParser.packages("package:com.good.app installer=null\npackage:com.play.app installer=com.android.vending\npackage:com.bad;reboot installer=null\n");
        assertEquals(2,p.size());assertEquals("",p.get("com.good.app"));assertEquals("com.android.vending",p.get("com.play.app"));
    }
    @Test public void exitStatusIsSeparateFromOutput() {
        DexoptParser.ShellResult r=DexoptParser.shellResult("Success\r\n__APPOPT_EXIT__0\r\n");assertEquals(0,r.exitCode);assertEquals("Success",r.output);
        r=DexoptParser.shellResult("Failure\n__APPOPT_EXIT__1\n");assertEquals(1,r.exitCode);
    }
    @Test(expected=IllegalArgumentException.class) public void truncatedCommandIsUnknownNeverSuccess() {DexoptParser.shellResult("Success");}
    @Test public void markerInsideOutputDoesNotImplyCompletion() {
        try {DexoptParser.shellResult("__APPOPT_EXIT__0\nStill running");fail();}catch(IllegalArgumentException expected) { }
    }
}
