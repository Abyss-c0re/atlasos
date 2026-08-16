package com.titanus2.controls;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

import java.util.List;

/**
 * SIM cards including UICC-off. Settings drops those from the SIMs list;
 * this screen keeps the row and the Off state.
 */
public class SimActivity extends Activity {
    public static final String ACTION_OPEN_SIMS = "com.titanus2.controls.action.OPEN_SIMS";

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private boolean paused;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("SIMs");

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        render();
    }

    @Override
    protected void onPause() {
        paused = true;
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_R) {
                render();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void render() {
        if (isFinishing() || root == null) return;
        root.removeAllViews();
        UiKit.title(root, "SIMs");
        UiKit.note(root, "Disable stays Off. Row is not deleted.");
        UiKit.toggle(root, "Disable phone calls", PhoneCalls.isDisabled(this), on -> {
            PhoneCalls.setDisabled(this, on);
            UiKit.toast(this, on ? "Phone calls Off" : "Phone calls On");
            h.post(this::render);
        });

        List<SimCards.Card> cards = SimCards.list(this);
        if (cards.isEmpty()) {
            UiKit.note(root, "No SIM records.");
        } else {
            for (final SimCards.Card c : cards) {
                String label = c.name;
                if (c.carrier != null && !c.carrier.isEmpty() && !c.carrier.equals(c.name)) {
                    label = c.name + " · " + c.carrier;
                }
                UiKit.toggle(root, label, c.uicc, on -> {
                    if (busy) return;
                    setCard(c, on);
                });
                TextView fact = UiKit.mono(root);
                fact.setText(c.fact());
            }
        }

        TextView hint = UiKit.mono(root);
        hint.setText("R refresh · Esc");
        UiKit.button(root, "Refresh", this::render);
    }

    private void setCard(SimCards.Card c, boolean on) {
        if (c == null || busy) return;
        busy = true;
        boolean ok = SimCards.setUicc(this, c.subId, on);
        if (!ok) {
            busy = false;
            UiKit.toast(this, "Could not set " + c.name);
            render();
            return;
        }
        UiKit.toast(this, c.name + " " + (on ? "On" : "Off"));
        h.postDelayed(() -> {
            busy = false;
            if (!paused && !isFinishing()) render();
        }, 800);
        h.postDelayed(() -> {
            if (!paused && !isFinishing()) render();
        }, 2200);
    }
}
