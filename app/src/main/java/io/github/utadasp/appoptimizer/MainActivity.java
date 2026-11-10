package io.github.utadasp.appoptimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A native, D-pad-first control surface. All privileged work belongs to OptimizerEngine. */
public final class MainActivity extends Activity {
    private static final int BG=0xff080e1b, SURFACE=0xff111d30, INSET=0xff0c1627,
            LINE=0xff26374e, TEXT=0xffeef6ff, MUTED=0xff91a5be, CYAN=0xff6be0d6;
    private OptimizerEngine engine;
    private final Set<String> selected=new LinkedHashSet<>();
    private final List<AppEntry> visible=new ArrayList<>();
    private LinearLayout appList;
    private TextView connection, freeValue, fullValue, selectedValue, appCount, workspaceTitle,
            phase, runDetail, netValue, terminal, countLabel;
    private ScrollView terminalScroll;
    private Button connect, refresh, optimize, reset, stop, modeFull, modeProfile;
    private View modeCaption, modesGroup, runButtonsGroup, outputCaption;
    private ProgressBar progress;
    private final List<Button> filterButtons=new ArrayList<>();
    private final List<View> selectionActions=new ArrayList<>();
    private int filter=0;
    private boolean profileMode=false;
    private String lastApps="";
    private String lastLog="";
    private final Runnable listener=this::render;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(BG);
        engine=OptimizerEngine.get(this);
        if(state!=null) {
            filter=state.getInt("filter",0);
            profileMode=state.getBoolean("profile",false);
            ArrayList<String> saved=state.getStringArrayList("selected");
            if(saved!=null) selected.addAll(saved);
        }
        build();
        render();
    }
    @Override protected void onStart(){super.onStart();engine.addListener(listener);render();}
    @Override protected void onStop(){engine.removeListener(listener);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putInt("filter",filter);out.putBoolean("profile",profileMode);
        out.putStringArrayList("selected",new ArrayList<>(selected));
    }
    @Override public void onBackPressed(){
        OptimizerEngine.Snapshot current=engine.snapshot();
        if(current.busy&&current.total>0){
            AlertDialog d=new AlertDialog.Builder(this).setTitle("A request is still running")
                    .setMessage("Keep App Optimizer open until the current request finishes. Stopping the queue will let the current app finish and leave the remaining apps untouched.")
                    .setNegativeButton("Keep running",null).setPositiveButton("Stop queue",(dialog,which)->engine.stopAfterCurrent()).create();
            styleDialog(d);d.show();d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        }else super.onBackPressed();
    }

    private void build() {
        LinearLayout root=column();root.setPadding(dp(38),dp(20),dp(38),dp(16));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff17243a,BG,0xff081820}));setContentView(root);
        LinearLayout header=row(Gravity.CENTER_VERTICAL);
        LinearLayout wordmark=column();
        TextView eyebrow=text("YOUR TV, RUNNING BETTER",10,CYAN);eyebrow.setLetterSpacing(.16f);wordmark.addView(eyebrow);
        wordmark.addView(text("App Optimizer",31,TEXT));
        connection=text("Connect once. Optimize whenever you want.",12,MUTED);wordmark.addView(connection);
        header.addView(wordmark,new LinearLayout.LayoutParams(0,dp(68),1));
        Button history=button("History",false,v->showHistory());header.addView(history,buttonParams(90));
        Button help=button("How it works",false,v->showHelp());header.addView(help,buttonParams(120));
        root.addView(header);

        LinearLayout stats=row(Gravity.CENTER_VERTICAL);stats.setPadding(0,dp(8),0,dp(13));
        freeValue=stat(stats,"STORAGE AVAILABLE","—",1.15f);
        fullValue=stat(stats,"FULLY COMPILED","—",1f);
        selectedValue=stat(stats,"SELECTED APPS","0",1f);
        root.addView(stats,new LinearLayout.LayoutParams(-1,dp(73)));

        LinearLayout content=row(Gravity.TOP);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout library=column();library.setPadding(dp(18),dp(15),dp(18),dp(8));library.setBackground(surface(SURFACE,22,LINE));
        LinearLayout libraryHeader=row(Gravity.CENTER_VERTICAL);
        appCount=text("Your apps",19,TEXT);libraryHeader.addView(appCount,new LinearLayout.LayoutParams(0,-2,1));
        refresh=button("Refresh",false,v->engine.refresh());libraryHeader.addView(refresh,buttonParams(90));
        library.addView(libraryHeader);
        LinearLayout filters=row(Gravity.CENTER_VERTICAL);filters.setPadding(0,dp(10),0,dp(10));
        String[] labels={"Sideloaded","All user apps","Not full"};
        for(int i=0;i<labels.length;i++) {final int index=i;Button b=button(labels[i],false,v->{filter=index;lastApps="";render();});
            filterButtons.add(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1);p.rightMargin=i==2?0:dp(7);filters.addView(b,p);}
        library.addView(filters);
        LinearLayout selectBar=row(Gravity.CENTER_VERTICAL);
        countLabel=text("",11,MUTED);selectBar.addView(countLabel,new LinearLayout.LayoutParams(0,dp(32),1));
        Button select=button("Select visible",false,v->{for(AppEntry a:visible)if(a.canOperate)selected.add(a.packageName);lastApps="";render();});
        Button clear=button("Clear",false,v->{selected.clear();lastApps="";render();});selectionActions.add(select);selectionActions.add(clear);
        selectBar.addView(select,buttonParams(108,31));selectBar.addView(clear,buttonParams(60,31));library.addView(selectBar);
        ScrollView appScroll=new ScrollView(this);appScroll.setFillViewport(false);appScroll.setClipToPadding(false);appScroll.setPadding(0,dp(6),0,dp(7));
        appList=column();appScroll.addView(appList);library.addView(appScroll,new LinearLayout.LayoutParams(-1,0,1));
        content.addView(library,new LinearLayout.LayoutParams(0,-1,1.65f));

        LinearLayout workspace=column();workspace.setPadding(dp(18),dp(14),dp(18),dp(12));workspace.setBackground(surface(SURFACE,22,LINE));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-1,1);wp.leftMargin=dp(16);content.addView(workspace,wp);
        workspaceTitle=text("Ready when you are",19,TEXT);workspace.addView(workspaceTitle);
        phase=text("Connect to read this TV’s app status.",11,MUTED);phase.setMaxLines(3);phase.setEllipsize(TextUtils.TruncateAt.END);phase.setPadding(0,dp(6),0,dp(10));workspace.addView(phase);
        connect=button("Connect to this TV",true,v->engine.connectAndScan());workspace.addView(connect,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView modeLabel=text("COMPILATION MODE",10,MUTED);modeLabel.setLetterSpacing(.1f);modeLabel.setPadding(0,dp(8),0,dp(5));workspace.addView(modeLabel);modeCaption=modeLabel;
        LinearLayout modes=row(Gravity.CENTER_VERTICAL);
        modeFull=button("Full speed",false,v->{profileMode=false;render();});modeProfile=button("Profile guided",false,v->{profileMode=true;render();});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(32),1);mp.rightMargin=dp(7);modes.addView(modeFull,mp);modes.addView(modeProfile,new LinearLayout.LayoutParams(0,dp(32),1));workspace.addView(modes);modesGroup=modes;
        runDetail=text("",10,MUTED);runDetail.setSingleLine(true);runDetail.setEllipsize(TextUtils.TruncateAt.END);runDetail.setPadding(0,dp(6),0,dp(9));workspace.addView(runDetail);
        LinearLayout runButtons=row(Gravity.CENTER_VERTICAL);
        optimize=button("Optimize selected",true,v->confirmRun(false));reset=button("Reset",false,v->confirmRun(true));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(0,dp(38),1);op.rightMargin=dp(8);runButtons.addView(optimize,op);runButtons.addView(reset,buttonParams(73,38));workspace.addView(runButtons);runButtonsGroup=runButtons;
        stop=button("Stop after current app",false,v->engine.stopAfterCurrent());LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(30));sp.topMargin=dp(6);workspace.addView(stop,sp);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(CYAN));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(4));pp.topMargin=dp(9);pp.bottomMargin=dp(6);workspace.addView(progress,pp);
        netValue=text("Net storage change  —",12,CYAN);workspace.addView(netValue);
        TextView outputLabel=text("LIVE OUTPUT · OK TO EXPAND",10,MUTED);outputLabel.setLetterSpacing(.1f);outputLabel.setPadding(0,dp(9),0,dp(5));workspace.addView(outputLabel);outputCaption=outputLabel;
        terminalScroll=new ScrollView(this);terminalScroll.setBackground(surface(INSET,12,LINE));terminalScroll.setPadding(dp(10),dp(8),dp(10),dp(8));terminalScroll.setFocusable(true);
        terminal=text("Output appears here during a run.",10,MUTED);terminal.setTypeface(Typeface.MONOSPACE);terminal.setTextIsSelectable(false);terminalScroll.addView(terminal);workspace.addView(terminalScroll,new LinearLayout.LayoutParams(-1,0,1));
        terminalScroll.setOnClickListener(v->{TextView copy=text(lastLog.isEmpty()?"No output yet.":lastLog,12,TEXT);copy.setTypeface(Typeface.MONOSPACE);copy.setPadding(dp(22),dp(10),dp(22),dp(14));showSheet("Command output",copy,"Close",null);});

        TextView footer=text("Storage change measures net /data usage, including background activity. Status is read from Android, never inferred from history.",10,MUTED);
        footer.setPadding(dp(3),dp(12),0,0);root.addView(footer);
    }

    private void render() {
        if(isFinishing())return;
        OptimizerEngine.Snapshot s=engine.snapshot();
        int full=0;for(AppEntry a:s.apps)if("Full".equals(a.status))full++;
        connection.setText(s.connected?"●  Connected locally  ·  "+android.os.Build.MODEL+"  ·  Android "+android.os.Build.VERSION.RELEASE:
                s.waiting?"Accept ‘Allow USB debugging?’ on your TV.":"Local connection  ·  No computer needed after setup");
        connection.setTextColor(s.connected?CYAN:MUTED);
        freeValue.setText(s.totalBytes>0?size(s.freeBytes)+" free":"—");fullValue.setText(full+" / "+s.apps.size());selectedValue.setText(Integer.toString(selected.size()));
        for(int i=0;i<filterButtons.size();i++)setActive(filterButtons.get(i),filter==i);
        setActive(modeFull,!profileMode);setActive(modeProfile,profileMode);
        connect.setVisibility(s.connected?View.GONE:View.VISIBLE);connect.setEnabled(!s.busy);
        boolean compact=getResources().getConfiguration().screenHeightDp<620;
        boolean showModes=s.connected&&!s.busy;
        modeCaption.setVisibility(showModes&&!compact?View.VISIBLE:View.GONE);
        modesGroup.setVisibility(showModes?View.VISIBLE:View.GONE);
        runDetail.setVisibility(showModes&&!compact?View.VISIBLE:View.GONE);
        runButtonsGroup.setVisibility(showModes?View.VISIBLE:View.GONE);
        if(compact){LinearLayout.LayoutParams rp=(LinearLayout.LayoutParams)runButtonsGroup.getLayoutParams();rp.topMargin=dp(9);runButtonsGroup.setLayoutParams(rp);}
        refresh.setEnabled(!s.busy&&s.connected);modeFull.setEnabled(!s.busy);modeProfile.setEnabled(!s.busy);
        for(View v:selectionActions)v.setEnabled(!s.busy);
        optimize.setEnabled(!s.busy&&s.connected&&!selected.isEmpty());reset.setEnabled(!s.busy&&s.connected&&!selected.isEmpty());
        for(Button b:new Button[]{connect,refresh,optimize,reset,modeFull,modeProfile})b.setAlpha(b.isEnabled()?1f:.38f);
        stop.setVisibility(s.busy&&s.total>0?View.VISIBLE:View.GONE);stop.setEnabled(!s.stopRequested);stop.setText(s.stopRequested?"Stopping after current app…":"Stop after current app");
        workspaceTitle.setText(s.busy?(s.total>0?"Working through your apps":"Connecting & checking"):s.error!=null&&!s.error.isEmpty()?"Attention needed":"Ready when you are");
        String phaseText=s.phase==null?"":s.phase;
        if(s.busy&&s.total>0)phaseText=Math.min(s.total,s.completed+1)+" of "+s.total+"  ·  "+phaseText;
        if(s.error!=null&&!s.error.isEmpty())phaseText=s.error;
        phase.setText(phaseText.isEmpty()?(s.connected?"Choose apps, then optimize or reset.":"Allow local debugging access to get started."):phaseText);
        phase.setTextColor(s.error!=null&&!s.error.isEmpty()?0xffffbd9b:MUTED);
        runDetail.setText(profileMode?"Less storage · uses learned profiles":"Full compilation · speed gains vary");
        progress.setVisibility(s.busy?View.VISIBLE:View.GONE);progress.setIndeterminate(true);
        netValue.setText("Net storage change  "+(s.batchMeasured?signed(s.batchDeltaBytes):"—"));
        String log=s.log==null?"":s.log;if(!log.equals(lastLog)){lastLog=log;terminal.setText(log.isEmpty()?"Output appears here during a run.":log);terminalScroll.post(()->terminalScroll.fullScroll(View.FOCUS_DOWN));}
        if(s.busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        StringBuilder key=new StringBuilder().append(filter).append(s.busy).append(selected);
        for(AppEntry a:s.apps)key.append(a.packageName).append(a.status).append(a.details);
        if(!key.toString().equals(lastApps)){lastApps=key.toString();renderApps(s);}
    }

    private void renderApps(OptimizerEngine.Snapshot s) {
        View focus=getCurrentFocus();String restore=focus==null?null:(focus.getTag() instanceof String?(String)focus.getTag():null);
        appList.removeAllViews();visible.clear();
        for(AppEntry a:s.apps){
            if(filter==0&&!a.sideloaded)continue;if(filter==2&&"Full".equals(a.status))continue;visible.add(a);
            LinearLayout row=row(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));
            row.setTag(a.packageName);row.setFocusable(a.canOperate&&!s.busy);row.setClickable(a.canOperate&&!s.busy);
            row.setBackground(surface(selected.contains(a.packageName)?0xff193a43:INSET,13,selected.contains(a.packageName)?0xff398b8c:LINE));
            row.setOnFocusChangeListener((v,has)->v.setBackground(surface(has?0xff23465a:selected.contains(a.packageName)?0xff193a43:INSET,13,has?CYAN:selected.contains(a.packageName)?0xff398b8c:LINE)));
            row.setOnClickListener(v->{if(selected.contains(a.packageName))selected.remove(a.packageName);else selected.add(a.packageName);lastApps="";render();});
            row.setOnLongClickListener(v->{
                String info=a.packageName+"\nVersion "+a.versionName+"\nInstaller: "+(a.installer==null||a.installer.isEmpty()?"Not recorded":a.installer)
                        +"\n\n"+a.status+"\n"+a.details+(a.debuggable?"\n\nThis is a debug build. Android may keep it in verification mode.":"");
                AlertDialog d=new AlertDialog.Builder(this).setTitle(a.label).setMessage(info).setPositiveButton("Close",null).create();styleDialog(d);d.show();return true;
            });
            TextView check=text(selected.contains(a.packageName)?"✓":"○",19,selected.contains(a.packageName)?CYAN:MUTED);check.setGravity(Gravity.CENTER);row.addView(check,new LinearLayout.LayoutParams(dp(28),-1));
            ImageView icon=new ImageView(this);try{icon.setImageDrawable(getPackageManager().getApplicationIcon(a.packageName));}catch(Exception ignored){icon.setImageResource(android.R.drawable.sym_def_app_icon);}
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(38),dp(38));ip.leftMargin=dp(7);ip.rightMargin=dp(12);row.addView(icon,ip);
            LinearLayout names=column();TextView name=text(a.label,16,TEXT);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name);
            TextView pkg=text(a.packageName,10,MUTED);pkg.setSingleLine(true);pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);names.addView(pkg);
            TextView source=text(!a.canOperate?"App Optimizer · protected":a.debuggable?"Debug build":a.sideloaded?"Sideloaded / installer unknown":"Store installed",10,MUTED);names.addView(source);
            row.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            TextView badge=text(statusLabel(a.status),10,"Full".equals(a.status)?CYAN:0xffc9d8eb);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(8),dp(5),dp(8),dp(5));badge.setBackground(surface("Full".equals(a.status)?0xff15383e:0xff233146,8,Color.TRANSPARENT));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(91),-2);bp.leftMargin=dp(9);row.addView(badge,bp);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(76));rp.bottomMargin=dp(7);appList.addView(row,rp);
            if(a.packageName.equals(restore))row.requestFocus();
        }
        appCount.setText("Your apps");countLabel.setText(visible.size()+" shown  ·  hold OK for details");
        if(visible.isEmpty()){
            TextView empty=text(s.connected?"No apps in this view. Try another filter.":"Connect to this TV to read its installed apps and compilation status.",15,MUTED);
            empty.setPadding(dp(16),dp(36),dp(16),dp(30));empty.setGravity(Gravity.CENTER);appList.addView(empty);
        }
    }

    private void confirmRun(boolean resetRun) {
        List<String> names=new ArrayList<>(selected);
        if(names.isEmpty())return;
        String title=resetRun?"Remove compiled code from "+names.size()+" app"+(names.size()==1?"":"s")+"?":"Optimize "+names.size()+" app"+(names.size()==1?"":"s")+"?";
        String message=resetRun?"Remove precompiled methods using verification-only mode (interpretation on Android 7 Full). Your app data, sign-ins and learned profiles stay intact. This does not restore the exact previous state; Android may optimize again later.\n\nA full post-install profile reset is restricted on this TV’s firmware. This action deliberately uses verification-only compilation instead.":
                (profileMode?"Use existing profiles for a smaller compilation footprint.":"Fully compile app code with Android’s speed mode. This can use additional storage.")+"\n\nApps run one at a time. Leave App Optimizer open. You can stop the queue after the current app; a running request will never be automatically repeated.";
        message+="\n\nNet storage change includes background activity. Compilation is not a guarantee of faster streaming.";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("Cancel",null).setPositiveButton(resetRun?"Remove code":"Start",(d,w)->engine.run(names,resetRun,profileMode)).create();
        styleDialog(dialog);dialog.show();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void showHelp() {
        LinearLayout body=column();body.setPadding(dp(28),dp(12),dp(28),dp(12));
        String copy=(BuildConfig.FULL_EDITION?"FULL EDITION · Android 7+\nAndroid 14+ uses primary APK/split code only, with capability checks. Android 7 may show Unknown status. Actual hardware validation: Android 12.\n\n":"TV EDITION · Android 8–13\n\n")+"ONE-TIME SETUP\nTurn on USB / network debugging in this TV’s Developer options. Select Connect, then accept ‘Allow USB debugging?’ with ‘Always allow’ checked. Debugging must remain enabled for future runs. This app connects only to this TV at 127.0.0.1:5555; wireless pairing / TLS is not supported.\n\nREADING APP STATUS\nFull: all reported code paths use speed compilation.\nProfile guided: Android compiled profile-selected code.\nVerified only: code was checked, without full compilation.\nInterpreted: Android 7 uses interpretation rather than bytecode method compilation.\nMixed: code paths use different compilation modes.\nUnknown: Android did not expose a usable status.\nDebug builds may remain verified only even after a successful request. A successful command is not the same as verified full compilation.\n\nSTORAGE & RESET\nThe reported change is net /data usage, including other TV activity. A negative value means space was freed. Reset removes precompiled code and switches the selected apps to verification-only mode. App data, sign-ins and learned profiles stay intact. It does not restore the exact previous state; Android may optimize again later. A full post-install profile reset is restricted on this TV’s firmware, so this action deliberately uses verification-only compilation instead.\n\nYOUR CONTROL\nOnly user-installed apps can be changed. Sideloaded is an installer-based estimate. No scheduled runs, automatic retries, root, accessibility, or background monitoring. On interruption, refresh status before deciding whether to retry.\n\nDISCONNECT\nTurning debugging off prevents commands. To remove trust completely, use Developer options → Revoke USB debugging authorizations. This also revokes other trusted debugging clients. Android may require authorization again after revocation or inactivity.";
        TextView info=text(copy,14,TEXT);info.setLineSpacing(dp(3),1);body.addView(info);
        Button settings=button("Open Developer options",false,v->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(44));p.topMargin=dp(20);body.addView(settings,p);
        showSheet("How App Optimizer works",body,"Close",null);
    }

    private void showHistory() {
        OptimizerEngine.Snapshot s=engine.snapshot();LinearLayout body=column();body.setPadding(dp(22),dp(8),dp(22),dp(14));
        TextView note=text("Recent requests · stored only on this TV. Results reflect the status checked after each request.",12,MUTED);note.setPadding(0,0,0,dp(15));body.addView(note);
        if(s.history.isEmpty())body.addView(text("No runs yet. Your first result will appear here.",15,TEXT));
        for(RunRecord r:s.history){
            LinearLayout card=column();card.setPadding(dp(15),dp(12),dp(15),dp(12));card.setBackground(surface(INSET,12,LINE));
            card.addView(text(r.label+"  ·  "+r.mode,16,TEXT));
            String when=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new java.util.Date(r.timestamp));
            card.addView(text(when+"  ·  "+r.outcome+"  ·  "+Math.max(1,r.durationMs/1000)+"s",12,MUTED));
            card.addView(text(statusLabel(r.statusBefore)+" → "+statusLabel(r.statusAfter)+"    "+(r.measured?signed(r.deltaBytes):"Storage not measured"),12,CYAN));
            TextView details=text(r.output,11,MUTED);details.setMaxLines(7);details.setEllipsize(TextUtils.TruncateAt.END);details.setTypeface(Typeface.MONOSPACE);details.setPadding(0,dp(7),0,0);card.addView(details);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(9);body.addView(card,cp);
        }
        showSheet("Run history",body,"Close",s.busy?null:()->new AlertDialog.Builder(this).setTitle("Clear local history?").setMessage("This only removes the history shown here. Compilation is unchanged.").setNegativeButton("Keep history",null).setPositiveButton("Clear",(d,w)->engine.clearHistory()).show());
    }
    private void showSheet(String title,View body,String close,Runnable clear) {
        ScrollView scroll=new ScrollView(this);scroll.addView(body);scroll.setFocusable(true);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(close,null);
        if(clear!=null)b.setNeutralButton("Clear history",(d,w)->clear.run());AlertDialog d=b.create();styleDialog(d);d.show();
        if(d.getWindow()!=null)d.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(110),dp(760)),(int)(getResources().getDisplayMetrics().heightPixels*.82));
    }
    private void styleDialog(AlertDialog dialog){dialog.setOnShowListener(d->{if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(surface(SURFACE,24,LINE));});}
    private String statusLabel(String s){return s==null||s.isEmpty()?"Unknown":s;}
    private TextView stat(LinearLayout parent,String label,String initial,float weight) {
        LinearLayout card=column();card.setPadding(dp(17),dp(7),dp(17),dp(7));card.setBackground(surface(0xbb132237,14,LINE));
        TextView cap=text(label,10,MUTED);cap.setLetterSpacing(.09f);card.addView(cap);TextView value=text(initial,21,TEXT);card.addView(value);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,weight);p.rightMargin=dp(10);parent.addView(card,p);return value;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(int gravity){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(gravity);return v;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setTypeface(Typeface.create(size>=16?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    private Button button(String label,boolean primary,View.OnClickListener action){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(12);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(9),dp(3),dp(9),dp(3));b.setTextColor(primary?BG:TEXT);b.setStateListAnimator(null);
        b.setBackground(surface(primary?CYAN:0xff1b2b42,10,primary?CYAN:LINE));
        b.setOnFocusChangeListener((v,f)->{boolean active=Boolean.TRUE.equals(v.getTag());b.setBackground(surface(f?0xffd5fff8:active?0xff244953:primary?CYAN:0xff1b2b42,10,f?Color.WHITE:active?CYAN:primary?CYAN:LINE));b.setTextColor(f?BG:primary?BG:TEXT);});
        b.setOnClickListener(action);return b;
    }
    private void setActive(Button b,boolean active){b.setTag(active);if(!b.hasFocus()){b.setBackground(surface(active?0xff244953:0xff1b2b42,10,active?CYAN:LINE));b.setTextColor(active?CYAN:TEXT);}}
    private LinearLayout.LayoutParams buttonParams(int width){return buttonParams(width,42);}
    private LinearLayout.LayoutParams buttonParams(int width,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(width),dp(height));p.leftMargin=dp(7);return p;}
    private GradientDrawable surface(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String size(long bytes){return bytes>=1024L*1024*1024?String.format(Locale.US,"%.2f GiB",bytes/(1024d*1024*1024)):String.format(Locale.US,"%.1f MiB",bytes/(1024d*1024));}
    private static String signed(long bytes){return (bytes>0?"+":bytes<0?"−":"")+size(Math.abs(bytes));}
}
