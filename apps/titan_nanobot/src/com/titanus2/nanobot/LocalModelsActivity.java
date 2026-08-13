package com.titanus2.nanobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple flow for local AI:
 *   1) Is the engine installed? (system / Magisk / lab)
 *   2) Only then download models
 *   3) Start model → use offline
 */
public class LocalModelsActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;
    private static final int C_OK = 0xFF0E2A1C;
    private static final int C_BAD = 0xFF3A1515;
    private static final int C_WARN = 0xFF2A2410;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private TextView stepBanner;
    private TextView status;
    private TextView progressTxt;
    private ProgressBar bar;
    private LinearLayout downloadSection;
    private LinearLayout localList;
    private LinearLayout presetList;
    private PeerClient peer;
    private boolean engineOk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);

        if (!PrivacyPrefs.localLlamaEnabled(this)) {
            PrivacyPrefs.setLocalLlamaEnabled(this, true); // user opened this screen — allow
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        TextView title = new TextView(this);
        title.setText("On-device AI");
        title.setTextColor(C_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = pill("Back", false);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(28));

        col.addView(body(
            "Works offline on this phone.\n\n"
                + "Step 1 — Install engine (once)\n"
                + "Step 2 — Download a model\n"
                + "Step 3 — Tap Start"));

        stepBanner = banner("Checking engine…", C_WARN);
        col.addView(stepBanner);

        status = mono("…");
        col.addView(status);

        // Sticky download progress (always in layout, tall enough)
        LinearLayout progCard = card(C_PANEL);
        TextView progTitle = new TextView(this);
        progTitle.setText("Download progress");
        progTitle.setTextColor(C_ACCENT);
        progTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        progCard.addView(progTitle);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setMinHeight(dp(28));
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        blp.topMargin = dp(8);
        blp.bottomMargin = dp(6);
        progCard.addView(bar, blp);
        progressTxt = new TextView(this);
        progressTxt.setTextColor(C_FG);
        progressTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        progressTxt.setTypeface(Typeface.MONOSPACE);
        progressTxt.setText("Idle — no download");
        progCard.addView(progressTxt);
        col.addView(progCard);

        LinearLayout ctl = new LinearLayout(this);
        Button stop = pill("Stop", false);
        stop.setOnClickListener(v -> io.execute(() -> {
            LlamaRuntime.stop(this);
            h.post(() -> { toast("Stopped"); refresh(); });
        }));
        Button refreshBtn = pill("Recheck engine", true);
        refreshBtn.setOnClickListener(v -> refresh());
        ctl.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        g.leftMargin = dp(8);
        ctl.addView(refreshBtn, g);
        col.addView(pad(ctl));

        col.addView(section("Your models on this phone"));
        localList = new LinearLayout(this);
        localList.setOrientation(LinearLayout.VERTICAL);
        col.addView(localList);

        downloadSection = new LinearLayout(this);
        downloadSection.setOrientation(LinearLayout.VERTICAL);
        downloadSection.addView(section("Download a model (only if engine is ready)"));
        downloadSection.addView(body(
            "Suggestions are the smallest / newest (≤ ~0.5B params).\n"
                + "Or paste any Hugging Face link / model name below."));
        presetList = new LinearLayout(this);
        presetList.setOrientation(LinearLayout.VERTICAL);
        downloadSection.addView(presetList);

        downloadSection.addView(section("Custom model (paste)"));
        downloadSection.addView(body(
            "Examples:\n"
                + "• Qwen/Qwen2.5-0.5B-Instruct-GGUF\n"
                + "• https://huggingface.co/…/something.gguf\n"
                + "Repo names auto-pick Q4_K_M when available.\n\n"
                + "On Start we probe tool calling. If the model cannot call tools, "
                + "you get a clear warning and chat-only mode."));
        final EditText customIn = new EditText(this);
        customIn.setHint("org/model-GGUF or https://huggingface.co/…/file.gguf");
        customIn.setHintTextColor(C_MUT);
        customIn.setTextColor(C_FG);
        customIn.setSingleLine(false);
        customIn.setMinLines(2);
        customIn.setMaxLines(4);
        customIn.setBackground(roundField());
        customIn.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cip = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cip.bottomMargin = dp(8);
        customIn.setLayoutParams(cip);
        downloadSection.addView(customIn);
        Button customDl = pill("Resolve & download", true);
        customDl.setOnClickListener(v -> {
            if (!engineOk) {
                toast("Install the engine first");
                return;
            }
            String raw = customIn.getText() != null ? customIn.getText().toString().trim() : "";
            if (raw.isEmpty()) {
                toast("Paste a Hugging Face link or org/name");
                return;
            }
            progressTxt.setText("Resolving on Hugging Face…\n" + raw);
            setBannerColor(stepBanner, C_WARN);
            stepBanner.setText("Looking up model on Hugging Face…");
            io.execute(() -> {
                try {
                    LlamaManager.ResolvedDownload r = LlamaManager.resolveCustomInput(raw);
                    h.post(() -> {
                        progressTxt.setText(r.note + "\n→ " + r.filename + "\n" + r.url);
                        toast(r.note);
                    });
                    // confirm on UI thread then download
                    h.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Download this GGUF?")
                        .setMessage(r.note + "\n\nFile: " + r.filename + "\n\n" + r.url
                            + "\n\nAfter Start we will test tool calling on custom models.")
                        .setPositiveButton("Download", (d, w) -> {
                            PrivacyPrefs.markCustomGguf(this, r.filename);
                            startDownload(r.url, r.filename);
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
                } catch (Exception e) {
                    h.post(() -> {
                        progressTxt.setText("Could not resolve:\n" + e.getMessage());
                        toast(e.getMessage());
                        refresh();
                    });
                }
            });
        });
        downloadSection.addView(padBtn(customDl));
        col.addView(downloadSection);

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        LlamaRuntime.Probe p = LlamaRuntime.probe(this);
        engineOk = p.present;
        status.setText(LlamaRuntime.statusLine(this));

        if (!engineOk) {
            stepBanner.setText("Step 1 of 3 — Engine NOT found\n\n"
                + "Install on PC:\n"
                + "  packages/titan2_llama/install_to_device.sh\n"
                + "or next ROM build: WITH_LLAMA=1\n\n"
                + "Model download is locked until the engine is present.");
            setBannerColor(stepBanner, C_BAD);
            downloadSection.setVisibility(View.GONE);
        } else if (!LlamaRuntime.isServerUp()) {
            stepBanner.setText("Step 2 of 3 — Engine ready (" + p.source + ")\n\n"
                + "Download a model below, then tap Start.");
            setBannerColor(stepBanner, C_OK);
            downloadSection.setVisibility(View.VISIBLE);
        } else {
            stepBanner.setText("Step 3 — Running offline on this phone\n"
                + "API " + LlamaRuntime.baseUrl());
            setBannerColor(stepBanner, C_OK);
            downloadSection.setVisibility(View.VISIBLE);
        }

        localList.removeAllViews();
        List<File> files = LlamaManager.listLocalGguf(this);
        boolean any = false;
        for (File f : files) {
            if (f.getName().endsWith(".partial")) {
                localList.addView(partialRow(f));
                any = true;
                continue;
            }
            if (!LlamaManager.isComplete(f)) continue;
            any = true;
            localList.addView(modelRow(f));
        }
        if (!any) {
            localList.addView(body(engineOk
                ? "No models yet — pick a download below."
                : "Install the engine first (see red box above)."));
        }

        presetList.removeAllViews();
        if (engineOk) {
            for (LlamaManager.Preset pr : LlamaManager.presets()) {
                presetList.addView(presetRow(pr));
            }
        }
    }

    private LinearLayout modelRow(File f) {
        LinearLayout row = card(C_OK);
        TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setText(prettyName(f.getName()) + "\n"
            + LlamaManager.formatSize(f.length())
            + "\n" + f.getAbsolutePath());
        row.addView(t);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button run = pill(engineOk ? "Start" : "Engine missing", true);
        run.setEnabled(engineOk);
        run.setOnClickListener(v -> startModel(f));
        Button del = pill("Delete", false);
        del.setOnClickListener(v -> confirmDelete(f));
        actions.addView(run, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ml.leftMargin = dp(8);
        actions.addView(del, ml);
        row.addView(actions);
        return row;
    }

    private LinearLayout partialRow(File f) {
        LinearLayout row = card(C_WARN);
        TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setText("Incomplete download:\n" + f.getName() + "\n"
            + LlamaManager.formatSize(f.length()) + " — resume or delete");
        row.addView(t);
        Button del = pill("Delete incomplete", false);
        del.setOnClickListener(v -> confirmDelete(f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.addView(del, lp);
        return row;
    }

    private void confirmDelete(File f) {
        if (f == null) return;
        new AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage(prettyName(f.getName()) + "\n\n"
                + LlamaManager.formatSize(f.length()) + "\n"
                + f.getAbsolutePath() + "\n\n"
                + "Frees storage. You can download again later.")
            .setPositiveButton("Delete", (d, w) -> {
                progressTxt.setText("Deleting… (may use peer shell for /data/local/tmp)");
                io.execute(() -> {
                    // If this model is currently loaded, stop the engine first
                    try {
                        if (LlamaRuntime.isServerUp()) {
                            LlamaRuntime.stop(this);
                        }
                    } catch (Exception ignored) {}
                    // Stop peer briefly if file locked? usually stop llama is enough
                    boolean ok = LlamaManager.deleteModel(this, f);
                    h.post(() -> {
                        boolean gone = !f.exists();
                        if (ok || gone) {
                            progressTxt.setText("Deleted " + prettyName(f.getName()));
                            toast("Deleted");
                        } else {
                            progressTxt.setText(
                                "Delete failed for temp file.\n"
                                    + "Shell-owned under /data/local/tmp — peer rm also failed.\n"
                                    + "From PC: adb shell rm -f "
                                    + f.getAbsolutePath());
                            toast("Delete failed — see progress text");
                        }
                        refresh();
                    });
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private LinearLayout presetRow(LlamaManager.Preset p) {
        LinearLayout row = card(C_PANEL);
        TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setText(p.name + "\nAbout " + p.sizeHint + " · " + p.notes);
        row.addView(t);
        File local = LlamaManager.modelFile(this, p.filename);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        if (LlamaManager.isComplete(local)) {
            Button b = pill("Start", true);
            b.setOnClickListener(v -> startModel(local));
            Button del = pill("Delete", false);
            del.setOnClickListener(v -> confirmDelete(local));
            actions.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.leftMargin = dp(8);
            actions.addView(del, ml);
        } else {
            Button b = pill("Download (" + p.sizeHint + ")", true);
            b.setOnClickListener(v -> {
                if (!engineOk) {
                    toast("Install the engine first");
                    return;
                }
                startDownload(p.url, p.filename);
            });
            actions.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.addView(actions);
        return row;
    }

    private void startModel(File f) {
        if (!engineOk) {
            toast("Engine not installed");
            return;
        }
        final boolean custom = PrivacyPrefs.isCustomGguf(this, f.getName());
        progressTxt.setText("Starting " + prettyName(f.getName()) + "…"
            + (custom ? "\n(Custom — will probe tool calling)" : ""));
        toast(custom ? "Starting custom model…" : "Starting…");
        io.execute(() -> {
            PrivacyPrefs.setSelectedLocalModelPath(this, f.getAbsolutePath());
            if (LlamaRuntime.isServerUp()) LlamaRuntime.stop(this);
            String err = LlamaRuntime.start(this, f, 4096);
            if (err == null || LlamaRuntime.isServerUp()) {
                try {
                    // Custom GGUF: live tool-call probe before enabling tools
                    if (custom) {
                        h.post(() -> progressTxt.setText(
                            "Probing tool calling on custom model…\n"
                                + prettyName(f.getName())));
                        LlamaRuntime.ToolProbeResult probe =
                            LlamaRuntime.probeToolCalling(f.getAbsolutePath());
                        PrivacyPrefs.setToolsSupported(this, f.getAbsolutePath(), probe.supported);
                        LlamaRuntime.applyAsNanobotBackend(this, peer, f);
                        ProviderProfile loc = ProviderStore.get(this, "llama_local");
                        if (loc != null) {
                            loc.enabled = true;
                            loc.model = f.getAbsolutePath();
                            ProviderStore.upsert(this, loc);
                        }
                        h.post(() -> {
                            if (probe.supported) {
                                progressTxt.setText("Running: " + prettyName(f.getName())
                                    + "\nTools: supported ✓\n" + f.getAbsolutePath());
                                toast("Custom model ready — tools OK");
                            } else {
                                progressTxt.setText("Running: " + prettyName(f.getName())
                                    + "\nTools: NOT supported\n" + probe.detail
                                    + "\n" + f.getAbsolutePath());
                                new AlertDialog.Builder(this)
                                    .setTitle(probe.title())
                                    .setMessage(
                                        prettyName(f.getName()) + "\n\n"
                                            + probe.detail
                                            + "\n\nChat still works (offline). "
                                            + "Shell/tools stay off for this model. "
                                            + "Use a tools-capable GGUF or Remote/cloud for tools.")
                                    .setPositiveButton("OK", null)
                                    .show();
                                toast("Tool calling not supported on this custom model");
                            }
                            refresh();
                        });
                        return;
                    }
                    // Curated preset — tools on by default
                    PrivacyPrefs.setToolsSupported(this, f.getAbsolutePath(), true);
                    LlamaRuntime.applyAsNanobotBackend(this, peer, f);
                    ProviderProfile loc = ProviderStore.get(this, "llama_local");
                    if (loc != null) {
                        loc.enabled = true;
                        loc.model = f.getAbsolutePath();
                        ProviderStore.upsert(this, loc);
                    }
                    h.post(() -> {
                        progressTxt.setText("Running: " + prettyName(f.getName())
                            + "\n" + f.getAbsolutePath());
                        toast("Ready — Local mode uses this model");
                        refresh();
                    });
                } catch (Exception e) {
                    h.post(() -> {
                        progressTxt.setText("Started but chat link failed: " + e.getMessage());
                        toast(e.getMessage());
                        refresh();
                    });
                }
            } else {
                h.post(() -> {
                    progressTxt.setText(err);
                    toast(err);
                    refresh();
                });
            }
        });
    }

    private void startDownload(String url, String filename) {
        if (!engineOk) {
            toast("Engine missing — install first");
            return;
        }
        if (!downloading.compareAndSet(false, true)) {
            toast("Already downloading");
            return;
        }
        bar.setProgress(0);
        progressTxt.setText("Connecting…\n" + filename);
        // keep banner visible
        stepBanner.setText("Downloading model… leave this screen open.");
        setBannerColor(stepBanner, C_WARN);

        io.execute(() -> LlamaManager.download(this, url, filename, new LlamaManager.Progress() {
            @Override public void onProgress(long downloaded, long total, String status) {
                h.post(() -> {
                    int pct = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
                    if (total > 0) bar.setProgress((int) Math.min(1000, downloaded * 1000 / total));
                    else bar.setProgress((bar.getProgress() + 5) % 1000);
                    progressTxt.setText(
                        String.format(Locale.US, "Downloading  %d%%\n%s\n%s",
                            pct, status, filename));
                });
            }
            @Override public void onDone(File file) {
                h.post(() -> {
                    downloading.set(false);
                    bar.setProgress(1000);
                    progressTxt.setText("Done — " + prettyName(file.getName())
                        + "\n" + LlamaManager.formatSize(file.length())
                        + "\nTap Start on the model above.");
                    toast("Download finished");
                    refresh();
                });
            }
            @Override public void onError(String msg) {
                h.post(() -> {
                    downloading.set(false);
                    progressTxt.setText("Download failed:\n" + msg
                        + "\nYou can try again — partial files resume.");
                    toast(msg);
                    refresh();
                });
            }
        }));
    }

    private String prettyName(String filename) {
        if (filename == null) return "?";
        if (filename.toLowerCase(Locale.US).endsWith(".gguf"))
            return filename.substring(0, filename.length() - 5);
        return filename;
    }

    private TextView banner(String s, int bg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        setBannerColor(t, bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private void setBannerColor(TextView t, int bg) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), C_LINE);
        t.setBackground(g);
    }

    private LinearLayout card(int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), C_LINE);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(8));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_MUT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView mono(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_PANEL);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), C_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout pad(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout padBtn(Button b) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return pad(wrap);
    }

    private android.graphics.drawable.GradientDrawable roundField() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF1A1A24);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? C_ACCENT : 0xFF1A1A24);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), C_LINE);
        b.setBackground(g);
        b.setTextColor(primary ? 0xFF00343A : C_FG);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
