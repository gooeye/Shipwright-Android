package com.dishii.soh;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Full-screen first-run setup UI drawn over the game surface: three-step indicator,
 * progress bar with counts/percentage/time left, and optional action buttons.
 * All methods must be called on the UI thread.
 */
public class SetupOverlay {

    public static final int STEP_PREPARE = 0;
    public static final int STEP_ROM = 1;
    public static final int STEP_BUILD = 2;

    private final Activity activity;
    private final View root;
    private final TextView subtitle;
    private final TextView status;
    private final ProgressBar progress;
    private final TextView detail;
    private final TextView percent;
    private final TextView hint;
    private final View actions;
    private final Button primaryAction;
    private final Button secondaryAction;
    private final View[] stepViews = new View[3];
    private final View[] stepLines = new View[2];

    private int activeStep = -1;
    private boolean visible;
    private ValueAnimator pulse;

    // Time-left estimate (exponential moving average of throughput).
    private long rateKey = -1;
    private long lastSampleTime;
    private long lastSampleDone;
    private double smoothedRate;

    public SetupOverlay(Activity activity, ViewGroup parent) {
        this.activity = activity;
        root = LayoutInflater.from(activity).inflate(R.layout.setup_overlay, parent, false);
        parent.addView(root);
        root.setVisibility(View.GONE);

        subtitle = root.findViewById(R.id.setup_subtitle);
        status = root.findViewById(R.id.setup_status);
        progress = root.findViewById(R.id.setup_progress);
        detail = root.findViewById(R.id.setup_detail);
        percent = root.findViewById(R.id.setup_percent);
        hint = root.findViewById(R.id.setup_hint);
        actions = root.findViewById(R.id.setup_actions);
        primaryAction = root.findViewById(R.id.setup_primary_action);
        secondaryAction = root.findViewById(R.id.setup_secondary_action);

        stepViews[0] = root.findViewById(R.id.setup_step_1);
        stepViews[1] = root.findViewById(R.id.setup_step_2);
        stepViews[2] = root.findViewById(R.id.setup_step_3);
        stepLines[0] = root.findViewById(R.id.setup_line_1);
        stepLines[1] = root.findViewById(R.id.setup_line_2);

        int[] labels = { R.string.setup_step_prepare, R.string.setup_step_rom, R.string.setup_step_build };
        for (int i = 0; i < 3; i++) {
            ((TextView) stepViews[i].findViewById(R.id.step_number)).setText(String.valueOf(i + 1));
            ((TextView) stepViews[i].findViewById(R.id.step_label)).setText(labels[i]);
        }
        renderSteps();
    }

    public boolean isVisible() {
        return visible;
    }

    public void show() {
        if (visible) {
            return;
        }
        visible = true;
        root.animate().cancel();
        root.setAlpha(0f);
        root.setVisibility(View.VISIBLE);
        root.bringToFront();
        root.animate().alpha(1f).setStartDelay(0).setDuration(220).withEndAction(null).start();
    }

    /** Fades the overlay out after a short beat so the user sees the final state. */
    public void dismiss(long delayMs, Runnable after) {
        if (!visible) {
            if (after != null) {
                after.run();
            }
            return;
        }
        root.animate().cancel();
        root.animate().alpha(0f).setStartDelay(delayMs).setDuration(320).withEndAction(() -> {
            root.setVisibility(View.GONE);
            visible = false;
            stopPulse();
            if (after != null) {
                after.run();
            }
        }).start();
    }

    public void setStep(int step) {
        if (step == activeStep) {
            return;
        }
        activeStep = step;
        renderSteps();
    }

    /** Marks every step as finished. */
    public void setAllDone() {
        activeStep = 3;
        renderSteps();
    }

    private void renderSteps() {
        stopPulse();
        for (int i = 0; i < 3; i++) {
            View circle = stepViews[i].findViewById(R.id.step_circle);
            TextView number = stepViews[i].findViewById(R.id.step_number);
            ImageView check = stepViews[i].findViewById(R.id.step_check);
            TextView label = stepViews[i].findViewById(R.id.step_label);
            int secondary = activity.getColor(R.color.setup_text_secondary);

            if (i < activeStep) {
                circle.setBackgroundResource(R.drawable.setup_step_done);
                number.setVisibility(View.GONE);
                check.setVisibility(View.VISIBLE);
                label.setTextColor(activity.getColor(R.color.setup_text_primary));
            } else if (i == activeStep) {
                circle.setBackgroundResource(R.drawable.setup_step_active);
                number.setVisibility(View.VISIBLE);
                check.setVisibility(View.GONE);
                number.setTextColor(activity.getColor(R.color.setup_accent));
                label.setTextColor(activity.getColor(R.color.setup_text_primary));
                startPulse(circle);
            } else {
                circle.setBackgroundResource(R.drawable.setup_step_idle);
                number.setVisibility(View.VISIBLE);
                check.setVisibility(View.GONE);
                number.setTextColor(secondary);
                label.setTextColor(secondary);
            }
        }
        for (int i = 0; i < 2; i++) {
            stepLines[i].setBackgroundTintList(ColorStateList.valueOf(activity.getColor(
                    i < activeStep ? R.color.setup_accent : R.color.setup_step_idle)));
        }
    }

    private void startPulse(View circle) {
        pulse = ObjectAnimator.ofFloat(circle, "alpha", 1f, 0.55f);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        for (View step : stepViews) {
            if (step != null) {
                step.findViewById(R.id.step_circle).setAlpha(1f);
            }
        }
    }

    /** A step whose length is unknown: animated bar, no counts. */
    public void setIndeterminate(int titleRes, int hintRes) {
        status.setText(titleRes);
        hint.setText(hintRes == 0 ? "" : activity.getString(hintRes));
        progress.setIndeterminate(true);
        detail.setText("");
        percent.setText("");
        hideActions();
        rateKey = -1;
    }

    /**
     * A step with known size. {@code unit} formats the counts, e.g. "files" or "MB".
     * {@code key} identifies the task so the time-left estimate resets between tasks.
     */
    public void setProgress(int titleRes, int hintRes, long done, long total, String unit, long key) {
        status.setText(titleRes);
        hint.setText(hintRes == 0 ? "" : activity.getString(hintRes));
        hideActions();
        if (total <= 0) {
            progress.setIndeterminate(true);
            detail.setText("");
            percent.setText("");
            return;
        }
        done = Math.max(0, Math.min(done, total));
        progress.setIndeterminate(false);
        int target = (int) (done * 1000 / total);
        progress.setProgress(target, true);
        percent.setText(String.format(Locale.US, "%d%%", done * 100 / total));

        String counts;
        if ("MB".equals(unit)) {
            counts = String.format(Locale.US, "%.1f / %.1f MB", done / 1048576.0, total / 1048576.0);
        } else {
            counts = String.format(Locale.US, "%,d / %,d %s", done, total, unit);
        }
        String eta = estimateTimeLeft(done, total, key);
        detail.setText(eta.isEmpty() ? counts : counts + "  ·  " + eta);
    }

    /** Shows a finished state with a full bar. */
    public void setDone(int titleRes) {
        status.setText(titleRes);
        progress.setIndeterminate(false);
        progress.setProgress(1000, true);
        percent.setText("100%");
        detail.setText("");
        hint.setText("");
        hideActions();
        rateKey = -1;
    }

    public void showPermissionRequest(Runnable onGrant) {
        subtitle.setText(R.string.setup_subtitle);
        setStep(STEP_PREPARE);
        status.setText(R.string.setup_permission_title);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        detail.setText("");
        percent.setText("");
        hint.setText(R.string.setup_permission_body);
        showActions(activity.getString(R.string.setup_permission_action), onGrant);
    }

    public void showError(String message, Runnable onRetry) {
        status.setText(R.string.setup_error_title);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        detail.setText("");
        percent.setText("");
        hint.setText(message);
        stopPulse();
        showActions(activity.getString(R.string.setup_retry), onRetry);
    }

    private void showActions(String label, Runnable onClick) {
        actions.setVisibility(View.VISIBLE);
        secondaryAction.setVisibility(View.GONE);
        primaryAction.setText(label);
        primaryAction.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.run();
            }
        });
    }

    private void hideActions() {
        actions.setVisibility(View.GONE);
    }

    private String estimateTimeLeft(long done, long total, long key) {
        long now = SystemClock.elapsedRealtime();
        if (key != rateKey) {
            rateKey = key;
            lastSampleTime = now;
            lastSampleDone = done;
            smoothedRate = 0;
            return "";
        }
        long dt = now - lastSampleTime;
        if (dt < 400) {
            return smoothedRate > 0 ? formatSeconds((total - done) / smoothedRate) : "";
        }
        double rate = (done - lastSampleDone) * 1000.0 / dt;
        smoothedRate = smoothedRate <= 0 ? rate : smoothedRate * 0.8 + rate * 0.2;
        lastSampleTime = now;
        lastSampleDone = done;
        if (smoothedRate <= 0) {
            return "";
        }
        return formatSeconds((total - done) / smoothedRate);
    }

    private static String formatSeconds(double seconds) {
        if (seconds < 1 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return "";
        }
        long s = Math.round(seconds);
        if (s < 60) {
            return String.format(Locale.US, "about %d s left", Math.max(1, s));
        }
        return String.format(Locale.US, "about %d min left", (s + 30) / 60);
    }
}
