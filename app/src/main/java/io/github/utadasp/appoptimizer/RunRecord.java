package io.github.utadasp.appoptimizer;

import org.json.JSONException;
import org.json.JSONObject;

/** One attempt, including failures/unknown outcomes; storage is a net filesystem observation. */
public final class RunRecord {
    public final String packageName,label,mode,outcome,output,statusBefore,statusAfter;
    public final long timestamp,durationMs,deltaBytes;
    public final boolean measured;
    RunRecord(String packageName,String label,String mode,String outcome,String output,String statusBefore,
              String statusAfter,long timestamp,long durationMs,long deltaBytes,boolean measured) {
        this.packageName=packageName;this.label=label;this.mode=mode;this.outcome=outcome;
        this.output=output;this.statusBefore=statusBefore;this.statusAfter=statusAfter;
        this.timestamp=timestamp;this.durationMs=durationMs;this.deltaBytes=deltaBytes;this.measured=measured;
    }
    JSONObject toJson() throws JSONException {
        return new JSONObject().put("package",packageName).put("label",label).put("mode",mode)
                .put("outcome",outcome).put("output",output).put("before",statusBefore).put("after",statusAfter)
                .put("timestamp",timestamp).put("duration",durationMs).put("delta",deltaBytes).put("measured",measured);
    }
    static RunRecord fromJson(JSONObject o) {
        return new RunRecord(o.optString("package"),o.optString("label"),o.optString("mode"),o.optString("outcome"),
                o.optString("output"),o.optString("before","Unknown"),o.optString("after","Unknown"),
                o.optLong("timestamp"),o.optLong("duration"),o.optLong("delta"),o.optBoolean("measured"));
    }
}
