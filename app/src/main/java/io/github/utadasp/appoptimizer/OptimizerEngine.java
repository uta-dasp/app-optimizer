package io.github.utadasp.appoptimizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One serial worker, no daemon, polling, automatic schedules, command retries or replay. */
public final class OptimizerEngine {
    public enum Mode {SPEED,SPEED_PROFILE,RESET}
    private static final long LOW_SPACE_BYTES=512L*1024*1024;
    private static final int MAX_HISTORY=100,MAX_LOG=16000;
    private static final class ShellFailure extends IOException {
        ShellFailure(int code,String text) {super("Command failed ("+code+"): "+text);}
    }
    private static OptimizerEngine instance;
    public static synchronized OptimizerEngine get(Context context) {
        if(instance==null) instance=new OptimizerEngine(context.getApplicationContext());
        return instance;
    }

    public static final class Snapshot {
        public final List<AppEntry> apps;
        public final List<RunRecord> history;
        public final boolean busy,connected,waiting,stopRequested,batchMeasured;
        public final String phase,currentPackage,log,error;
        public final int completed,total;
        public final long freeBytes,totalBytes,batchDeltaBytes;
        Snapshot(OptimizerEngine e) {
            apps=Collections.unmodifiableList(new ArrayList<>(e.apps));
            history=Collections.unmodifiableList(new ArrayList<>(e.history));
            busy=e.busy;connected=e.connected;waiting=e.waiting;stopRequested=e.stopRequested;batchMeasured=e.batchMeasured;
            phase=e.phase;currentPackage=e.currentPackage;log=e.log;error=e.error;
            completed=e.completed;total=e.total;freeBytes=e.freeBytes;totalBytes=e.totalBytes;batchDeltaBytes=e.batchDeltaBytes;
        }
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"app-optimizer");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Runnable> listeners=new CopyOnWriteArraySet<>();
    private List<AppEntry> apps=new ArrayList<>();
    private final List<RunRecord> history=new ArrayList<>();
    private boolean busy,connected,waiting,stopRequested,batchMeasured;
    private String phase="Connect to this TV to get started",currentPackage="",log="",error="";
    private int completed,total;
    private long freeBytes=-1,totalBytes=-1,batchDeltaBytes;

    private OptimizerEngine(Context c) {
        context=c;prefs=c.getSharedPreferences("optimizer",Context.MODE_PRIVATE);
        try {
            JSONArray saved=new JSONArray(prefs.getString("history","[]"));
            for(int i=0;i<saved.length() && i<MAX_HISTORY;i++) history.add(RunRecord.fromJson(saved.getJSONObject(i)));
        } catch(JSONException ignored) {error="Saved history could not be read";}
        batchDeltaBytes=prefs.getLong("lastBatchDelta",0);
        batchMeasured=prefs.getBoolean("lastBatchMeasured",false);
        if(prefs.contains("activePackage")) {
            history.add(0,new RunRecord(prefs.getString("activePackage",""),prefs.getString("activeLabel",""),
                    prefs.getString("activeMode",""),"Interrupted · outcome unknown",
                    "The app stopped before a result was saved. No command was retried. Refresh Android's status before acting again.",
                    "Unknown","Unknown",prefs.getLong("activeTime",System.currentTimeMillis()),0,0,false));
            persistHistory();
            error="A previous operation was interrupted. Its outcome is unknown; refresh before running again.";
        }
    }
    public synchronized Snapshot snapshot() {return new Snapshot(this);}
    public void addListener(Runnable listener) {listeners.add(listener);}
    public void removeListener(Runnable listener) {listeners.remove(listener);}
    private void changed() {main.post(()->{for(Runnable r:listeners) r.run();});}

    public void connectAndScan() {scanAsync(true);}
    public void refresh() {scanAsync(false);}
    public void scan() {refresh();}
    private void scanAsync(boolean allowApproval) {
        synchronized(this) {
            if(busy) return;
            busy=true;waiting=false;error="";phase="Connecting to this TV…";log="";
            completed=0;total=0;currentPackage="";stopRequested=false;
        }
        changed();
        worker.execute(()->{
            try(LocalAdb adb=connection(allowApproval)) {
                synchronized(this) {connected=true;waiting=false;phase="Reading apps and Android compilation records…";}
                changed();
                scanWith(adb);
                synchronized(this) {phase="Ready · " + apps.size() + " user apps";}
            } catch(Exception e) {failed(e);}
            finally {synchronized(this){busy=false;waiting=false;}changed();}
        });
    }
    private LocalAdb connection(boolean allowApproval) throws IOException {
        return LocalAdb.connect(context,allowApproval?90000:0,()->{
            synchronized(this) {waiting=true;phase="On the TV, allow App Optimizer and select Always allow";}
            changed();
        });
    }
    private String checked(LocalAdb adb,String command,long timeout,boolean stream) throws IOException {
        StringBuilder pending=new StringBuilder();
        String raw;
        try {
            raw=adb.shell(DexoptParser.checkedCommand(command),timeout,stream?chunk->{
                pending.append(chunk);
                int newline;
                while((newline=pending.indexOf("\n"))>=0) {
                    String line=pending.substring(0,newline+1);pending.delete(0,newline+1);appendVisibleLine(line);
                }
            }:null);
        } finally {
            if(stream && pending.length()>0) appendVisibleLine(pending.toString());
        }
        DexoptParser.ShellResult result;
        try {result=DexoptParser.shellResult(raw);} catch(IllegalArgumentException e) {throw new IOException(e.getMessage(),e);}
        if(result.exitCode!=0) throw new ShellFailure(result.exitCode,result.output);
        return result.output;
    }
    private void appendVisibleLine(String line) {
        String stripped=line.trim();
        if(!stripped.startsWith(DexoptParser.EXIT_MARKER) && !(stripped.length()>0&&DexoptParser.EXIT_MARKER.startsWith(stripped))) appendLog(line);
    }
    private void scanWith(LocalAdb adb) throws IOException {
        Map<String,String> installed=DexoptParser.packages(checked(adb,"pm list packages -3 -i --user 0",60000,false));
        if(installed.isEmpty()) throw new IOException("Android returned no readable user-app list");
        Map<String,DexoptParser.Status> states=readStates(adb);
        List<AppEntry> loaded=new ArrayList<>();
        PackageManager pm=context.getPackageManager();
        for(Map.Entry<String,String> entry:installed.entrySet()) {
            String name=entry.getKey(),label=name,version="";
            boolean debug=false,system=false;
            try {
                PackageInfo pi=pm.getPackageInfo(name,0);
                if(pi.applicationInfo!=null) {
                    label=pi.applicationInfo.loadLabel(pm).toString();
                    debug=(pi.applicationInfo.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
                    system=(pi.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
                }
                version=pi.versionName==null?"":pi.versionName;
            } catch(PackageManager.NameNotFoundException ignored) { }
            DexoptParser.Status status=states.get(name);
            if(status==null) status=DexoptParser.unknown();
            loaded.add(new AppEntry(name,label,entry.getValue(),status.label,status.details,version,
                    !"com.android.vending".equals(entry.getValue()),debug,!system&&!name.equals(context.getPackageName())));
        }
        loaded.sort(Comparator.comparing(a->a.label.toLowerCase(java.util.Locale.ROOT)));
        synchronized(this) {apps=loaded;}
        readSpace(adb);
        changed();
    }
    private Map<String,DexoptParser.Status> readStates(LocalAdb adb) throws IOException {
        if(BuildConfig.FULL_EDITION && Build.VERSION.SDK_INT>=34)
            return DexoptParser.parse(checked(adb,"pm art dump",60000,false),true);
        return DexoptParser.parse(checked(adb,"dumpsys package dexopt",60000,false));
    }
    private DexoptParser.Space readSpace(LocalAdb adb) throws IOException {
        DexoptParser.Space space;
        try {space=DexoptParser.space(checked(adb,"df -k /data",20000,false));}
        catch(IllegalArgumentException e) {throw new IOException(e.getMessage(),e);}
        synchronized(this) {freeBytes=space.freeBytes;totalBytes=space.totalBytes;}
        return space;
    }
    public void run(List<String> packageNames,Mode mode) {if(mode==null)throw new IllegalArgumentException("Mode required");run(packageNames,mode==Mode.RESET,mode==Mode.SPEED_PROFILE);}
    public void run(List<String> packageNames,boolean reset,boolean profile) {
        List<AppEntry> queue=new ArrayList<>();
        synchronized(this) {
            if(busy) return;
            if(!connected) {error="Connect and refresh the app list first";changed();return;}
            if(!BuildConfig.FULL_EDITION && Build.VERSION.SDK_INT>=34) {
                error="This version supports Android 8–13 compilation commands. Android 14+ requires separate ART validation.";changed();return;
            }
            if(packageNames==null || packageNames.isEmpty()) {error="Select at least one app";changed();return;}
            Map<String,AppEntry> known=new LinkedHashMap<>();for(AppEntry a:apps) known.put(a.packageName,a);
            for(String name:new LinkedHashSet<>(packageNames)) {
                AppEntry app=known.get(name);
                if(!DexoptParser.validPackage(name)||app==null||!app.canOperate) {
                    error="Only scanned user apps can be changed; App Optimizer and system apps are protected";changed();return;
                }
                queue.add(app);
            }
            busy=true;waiting=false;stopRequested=false;completed=0;total=queue.size();batchDeltaBytes=0;batchMeasured=false;
            prefs.edit().putBoolean("lastBatchMeasured",false).apply();
            log="";error="";phase=reset?"Preparing to remove compiled code…":"Preparing compilation…";
        }
        changed();
        worker.execute(()->runBatch(queue,reset,profile));
    }
    private void runBatch(List<AppEntry> queue,boolean reset,boolean profile) {
        long baseline=-1;
        String mode=reset?"Remove compiled code":profile?"Profile guided":"Full compilation";
        try(LocalAdb adb=connection(false)) {
            final boolean primaryOnly=BuildConfig.FULL_EDITION && Build.VERSION.SDK_INT>=34;
            final boolean legacyInterpret=BuildConfig.FULL_EDITION && Build.VERSION.SDK_INT<26;
            if(primaryOnly && !DexoptParser.supportsPrimaryDex(checked(adb,"pm help",60000,false)))
                throw new IOException("This Android version does not advertise primary-dex-only compilation. No operation was started, to avoid changing app dependencies.");
            baseline=readSpace(adb).freeBytes;
            for(AppEntry app:queue) {
                synchronized(this) {if(stopRequested) break;currentPackage=app.packageName;phase=mode+" · "+app.label;}
                changed();
                DexoptParser.Space before;
                try {before=readSpace(adb);} catch(IOException e) {
                    addRecord(new RunRecord(app.packageName,app.label,mode,"Not started","Could not measure free space; no command sent. "+safeMessage(e),app.status,"Unknown",System.currentTimeMillis(),0,0,false));
                    throw e;
                }
                if(!reset && before.freeBytes<LOW_SPACE_BYTES) {
                    addRecord(new RunRecord(app.packageName,app.label,mode,"Not started","Less than 512 MiB free; no command sent",app.status,app.status,System.currentTimeMillis(),0,0,false));
                    throw new IOException("Compilation stopped: keep at least 512 MiB free. Reset remains available.");
                }
                // Revalidate this exact user package immediately before modifying it; updates/uninstalls may occur after scanning.
                Map<String,String> current=DexoptParser.packages(checked(adb,"pm list packages -3 -i --user 0 "+app.packageName,20000,false));
                if(!current.containsKey(app.packageName)) {
                    addRecord(new RunRecord(app.packageName,app.label,mode,"Skipped","App is no longer installed as a user app",app.status,"Unknown",System.currentTimeMillis(),0,0,false));
                    synchronized(this){completed++;}changed();continue;
                }
                long started=System.currentTimeMillis(),clock=SystemClock.elapsedRealtime();
                if(!prefs.edit().putString("activePackage",app.packageName).putString("activeLabel",app.label)
                        .putString("activeMode",mode).putLong("activeTime",started).commit())
                    throw new IOException("Could not save the operation journal; nothing was sent");
                String output="",outcome="Completed",after="Unknown";
                long delta=0;boolean measured=false,uncertain=false,commandFinished=false,commandConfirmed=false;
                String command=DexoptParser.compileCommand(app.packageName,reset,profile,primaryOnly,legacyInterpret);
                appendLog("\n"+app.label+"\n$ "+command+"\n");
                try {
                    output=checked(adb,command,15*60*1000L,true);
                    commandFinished=true;
                    commandConfirmed=java.util.regex.Pattern.compile("(?m)^Success\\s*$").matcher(output).find();
                    if(!commandConfirmed) outcome="Not confirmed";
                    Map<String,DexoptParser.Status> status=readStates(adb);
                    DexoptParser.Status state=status.get(app.packageName);
                    if(state==null) state=DexoptParser.unknown();
                    after=state.label;
                    synchronized(this) {
                        List<AppEntry> updated=new ArrayList<>();
                        for(AppEntry entry:apps) updated.add(entry.packageName.equals(app.packageName)?entry.withStatus(state):entry);
                        apps=updated;
                    }
                    delta=before.freeBytes-readSpace(adb).freeBytes;measured=true;
                    String expected=reset?(legacyInterpret?"Interpreted":"Verified only"):profile?"Profile guided":"Full";
                    if("Completed".equals(outcome) && !expected.equals(after)) outcome="Completed · "+after;
                } catch(Exception e) {
                    uncertain=true;
                    outcome=commandFinished?(commandConfirmed?"Completed · verification unavailable":"Not confirmed"):e instanceof ShellFailure?"Failed":"Outcome unknown";
                    output=output+"\n"+safeMessage(e)+"\nNot retried. Refresh status before acting again.";
                    synchronized(this){
                        connected=false;
                        List<AppEntry> updated=new ArrayList<>();
                        for(AppEntry entry:apps) updated.add(entry.packageName.equals(app.packageName)?entry.withStatus(DexoptParser.unknown()):entry);
                        apps=updated;
                    }
                    try {delta=before.freeBytes-readSpace(adb).freeBytes;measured=true;}catch(Exception ignored) { }
                }
                addRecord(new RunRecord(app.packageName,app.label,mode,outcome,limit(output,8000),app.status,after,
                        started,SystemClock.elapsedRealtime()-clock,delta,measured));
                synchronized(this) {completed++;if(measured) {batchDeltaBytes=baseline-freeBytes;batchMeasured=true;}}
                changed();
                if(uncertain) throw new IOException("The last request or its verification could not finish. Remaining apps were not started; see history.");
            }
            synchronized(this) {phase=stopRequested?"Stopped after the current app":"Finished · "+completed+" of "+total;}
            try {readSpace(adb);synchronized(this){batchDeltaBytes=baseline-freeBytes;batchMeasured=true;}}catch(IOException ignored) { }
        } catch(Exception e) {failed(e);}
        finally {
            synchronized(this) {busy=false;waiting=false;currentPackage="";}
            Snapshot finalState=snapshot();
            prefs.edit().putLong("lastBatchDelta",finalState.batchDeltaBytes).putBoolean("lastBatchMeasured",finalState.batchMeasured).apply();
            changed();
        }
    }
    public synchronized void stopAfterCurrent() {if(busy){stopRequested=true;changed();}}
    public synchronized void clearHistory() {if(!busy){history.clear();completed=0;total=0;batchDeltaBytes=0;batchMeasured=false;prefs.edit().putLong("lastBatchDelta",0).putBoolean("lastBatchMeasured",false).apply();persistHistory();changed();}}
    private void addRecord(RunRecord record) {synchronized(this){history.add(0,record);while(history.size()>MAX_HISTORY)history.remove(history.size()-1);persistHistory();}}
    private void persistHistory() {
        JSONArray array=new JSONArray();
        try {for(RunRecord record:history) array.put(record.toJson());}
        catch(JSONException e) {error="Could not save full history";}
        prefs.edit().putString("history",array.toString()).remove("activePackage").remove("activeLabel").remove("activeMode").remove("activeTime").commit();
    }
    private synchronized void appendLog(String value) {log=limit(log+value,MAX_LOG);changed();}
    private static String limit(String value,int max) {return value.length()>max?value.substring(value.length()-max):value;}
    private void failed(Exception e) {synchronized(this){connected=false;waiting=false;error=safeMessage(e);phase="Needs attention";}changed();}
    private static String safeMessage(Exception e) {return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
