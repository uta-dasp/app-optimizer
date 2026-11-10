package io.github.utadasp.appoptimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsers: absence of evidence is Unknown, not an optimized badge. */
public final class DexoptParser {
    static final String EXIT_MARKER = "__APPOPT_EXIT__";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z][A-Za-z0-9_.]+)\\]\\s*$");
    private static final Pattern STATUS = Pattern.compile("\\[status=([^\\]]+)\\]");
    private static final Pattern REASON = Pattern.compile("\\[reason=([^\\]]+)\\]");
    private static final Pattern EXIT = Pattern.compile("(?:^|\\r?\\n)" + EXIT_MARKER + "([0-9]+)\\s*$");

    private DexoptParser() {}

    public static final class Status {
        public final String label, details;
        Status(String label,String details) {this.label=label;this.details=details;}
    }
    public static final class ShellResult {
        public final int exitCode;
        public final String output;
        ShellResult(int exitCode,String output) {this.exitCode=exitCode;this.output=output;}
    }
    public static final class Space {
        public final long totalBytes, freeBytes;
        Space(long totalBytes,long freeBytes) {this.totalBytes=totalBytes;this.freeBytes=freeBytes;}
    }
    public static boolean validPackage(String value) {
        return value != null && value.length() <= 255 && PACKAGE.matcher(value).matches();
    }
    public static String compileCommand(String packageName, boolean reset, boolean profile) {
        return compileCommand(packageName,reset,profile,false,false);
    }
    public static String compileCommand(String packageName, boolean reset, boolean profile, boolean primaryOnly) {
        return compileCommand(packageName,reset,profile,primaryOnly,false);
    }
    public static String compileCommand(String packageName, boolean reset, boolean profile, boolean primaryOnly, boolean legacyInterpret) {
        if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid package name");
        // This TV denies profile-clearing --reset even to shell. Explicit verify-only
        // removes compiled code without clearing learned profiles; never a retry/fallback.
        // Android 7 predates the verify filter: interpret-only leaves bytecode interpreted.
        return "pm compile -m " + (reset ? legacyInterpret?"interpret-only":"verify" : profile ? "speed-profile" : "speed")
                + " -f " + (primaryOnly?"--primary-dex ":"") + packageName;
    }
    /** A modern action must explicitly avoid ART's default dependency expansion. */
    public static boolean supportsPrimaryDex(String help) {
        return help!=null && Pattern.compile("(?m)^\\s*compile(?:\\s|\\[)").matcher(help).find()
                && Pattern.compile("(?m)^\\s*--primary-dex(?:\\s|:|$)").matcher(help).find();
    }
    public static String checkedCommand(String command) {
        // This method accepts only engine-owned literals or compileCommand's validated output.
        return command + "; printf '\\n" + EXIT_MARKER + "%s\\n' \"$?\"";
    }
    public static ShellResult shellResult(String text) {
        Matcher m = EXIT.matcher(text == null ? "" : text);
        if (!m.find()) throw new IllegalArgumentException("Command ended without an exit status; outcome unknown");
        return new ShellResult(Integer.parseInt(m.group(1)),text.substring(0,m.start()).trim());
    }
    public static Map<String,Status> parse(String text) {
        return parse(text,false);
    }
    public static Map<String,Status> parse(String text, boolean primaryOnly) {
        Map<String,Set<String>> states = new LinkedHashMap<>();
        Map<String,Set<String>> reasons = new LinkedHashMap<>();
        String current = null;
        boolean secondary=false;
        for (String line : text.split("\\r?\\n")) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                current = section.group(1);
                secondary=false;
                states.put(current,new LinkedHashSet<>());
                reasons.put(current,new LinkedHashSet<>());
                continue;
            }
            if (current == null) continue;
            if(primaryOnly && line.trim().startsWith("known secondary dex files:")) secondary=true;
            if(secondary) continue;
            Matcher s = STATUS.matcher(line);
            while(s.find()) states.get(current).add(s.group(1));
            Matcher r = REASON.matcher(line);
            while(r.find()) reasons.get(current).add(r.group(1));
        }
        Map<String,Status> result = new LinkedHashMap<>();
        for (Map.Entry<String,Set<String>> entry: states.entrySet()) {
            Set<String> filters = entry.getValue();
            String label = classify(filters);
            String details = filters.isEmpty() ? "No compilation record reported" : (primaryOnly?"Primary dex filters: ":"Filters: ") + join(filters);
            if (!reasons.get(entry.getKey()).isEmpty()) details += " · " + join(reasons.get(entry.getKey()));
            result.put(entry.getKey(),new Status(label,details));
        }
        return result;
    }
    private static String join(Iterable<String> values) {
        StringBuilder out=new StringBuilder();
        for(String value:values) {if(out.length()>0)out.append(", ");out.append(value);}
        return out.toString();
    }
    private static String classify(Set<String> filters) {
        if(filters.isEmpty()) return "Unknown";
        if(filters.size()>1) return "Mixed";
        String f=filters.iterator().next();
        if("speed".equals(f) || "everything".equals(f)) return "Full";
        if("speed-profile".equals(f) || "everything-profile".equals(f)) return "Profile guided";
        if("verify".equals(f)) return "Verified only";
        if("interpret-only".equals(f)) return "Interpreted";
        if("run-from-apk".equals(f) || "run-from-apk-fallback".equals(f) || "extract".equals(f)) return "Not compiled";
        return "Unknown";
    }
    public static Status unknown() {return new Status("Unknown","Android did not expose a readable compilation record");}

    /** User apps only; installer metadata is an origin hint, not proof of how an APK arrived. */
    public static Map<String,String> packages(String text) {
        Map<String,String> result=new LinkedHashMap<>();
        for(String line:text.split("\\r?\\n")) {
            line=line.trim();
            if(!line.startsWith("package:")) continue;
            String[] pieces=line.substring(8).split("\\s+");
            if(!validPackage(pieces[0])) continue;
            String installer="";
            for(String part:pieces) if(part.startsWith("installer=")) installer=part.substring(10);
            if("null".equals(installer)) installer="";
            result.put(pieces[0],installer);
        }
        return result;
    }
    public static Space space(String text) {
        Space result=null;
        for(String line:text.split("\\r?\\n")) {
            String[] fields=line.trim().split("\\s+");
            // The engine runs the fixed command df -k /data, not a user-supplied target.
            // Some TV firmware prints a bind-mount alias for this same filesystem.
            if(fields.length==6 && fields[5].startsWith("/") && fields[4].matches("[0-9]+%")) {
                try {
                    long total=Math.multiplyExact(Long.parseLong(fields[1]),1024L);
                    long used=Math.multiplyExact(Long.parseLong(fields[2]),1024L);
                    long free=Math.multiplyExact(Long.parseLong(fields[3]),1024L);
                    int percentage=Integer.parseInt(fields[4].substring(0,fields[4].length()-1));
                    if(total>0 && used>=0 && used<=total && free>=0 && free<=total && percentage<=100) {
                        if(result!=null) throw new IllegalArgumentException("Ambiguous storage output; more than one filesystem reported");
                        result=new Space(total,free);
                    }
                } catch(ArithmeticException | NumberFormatException ignored) { }
            }
        }
        if(result!=null) return result;
        throw new IllegalArgumentException("Could not read /data storage; no space estimate available");
    }
}
