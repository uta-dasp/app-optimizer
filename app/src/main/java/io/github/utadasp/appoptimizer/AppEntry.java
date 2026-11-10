package io.github.utadasp.appoptimizer;

/** Snapshot of one user-installed package. Compilation is read from Android, never inferred from history. */
public final class AppEntry {
    public final String packageName, label, installer, status, details, versionName;
    public final boolean sideloaded, debuggable, canOperate;

    public AppEntry(String packageName, String label, String installer, String status, String details,
                    String versionName, boolean sideloaded, boolean debuggable, boolean canOperate) {
        this.packageName = packageName; this.label = label; this.installer = installer;
        this.status = status; this.details = details; this.versionName = versionName;
        this.sideloaded = sideloaded; this.debuggable = debuggable; this.canOperate = canOperate;
    }

    AppEntry withStatus(DexoptParser.Status state) {
        return new AppEntry(packageName,label,installer,state.label,state.details,versionName,
                sideloaded,debuggable,canOperate);
    }
}
