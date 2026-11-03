package com.dishii.soh;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Pause menu: resume, save states, game settings, and touch controls options. UI thread only. */
final class QuickMenu {

    interface Listener {
        void onResumeGame();
        void onSaveState(int slot);
        void onLoadState(int slot);
        boolean hasSaveState(int slot);
        void onOpenSettings();
        void onEditControls();
        void onToggleControls();
        boolean areControlsHidden();
    }

    private static final int SLOT_COUNT = 3;

    private final Context context;
    private final Listener listener;
    private final View root;
    private final View card;
    private final Button loadButton;
    private final Button toggleControls;
    private final View[] slotViews = new View[SLOT_COUNT];
    private final List<Runnable> toggleRefreshers = new ArrayList<>();
    private int selectedSlot = 1;
    private boolean showing;

    QuickMenu(Context context, ViewGroup parent, Listener listener) {
        this.context = context;
        this.listener = listener;
        root = LayoutInflater.from(context).inflate(R.layout.quick_menu, parent, false);
        parent.addView(root);
        root.setVisibility(View.GONE);
        card = root.findViewById(R.id.quick_card);

        // Tapping outside the card resumes, like dismissing a dialog.
        root.setOnClickListener(v -> listener.onResumeGame());
        root.findViewById(R.id.quick_resume).setOnClickListener(v -> listener.onResumeGame());
        root.findViewById(R.id.quick_save_state).setOnClickListener(v -> listener.onSaveState(selectedSlot));
        loadButton = root.findViewById(R.id.quick_load_state);
        loadButton.setOnClickListener(v -> listener.onLoadState(selectedSlot));
        root.findViewById(R.id.quick_settings).setOnClickListener(v -> listener.onOpenSettings());
        root.findViewById(R.id.quick_edit_controls).setOnClickListener(v -> listener.onEditControls());
        toggleControls = root.findViewById(R.id.quick_toggle_controls);
        toggleControls.setOnClickListener(v -> listener.onToggleControls());

        LinearLayout slots = root.findViewById(R.id.quick_slots);
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i + 1;
            View chip = LayoutInflater.from(context).inflate(R.layout.quick_slot, slots, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(Math.round(8 * density));
            }
            slots.addView(chip, lp);
            ((TextView) chip.findViewById(R.id.slot_name)).setText(context.getString(R.string.quick_slot, slot));
            chip.setOnClickListener(v -> {
                selectedSlot = slot;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                refresh();
            });
            slotViews[i] = chip;
        }
    }

    /** Adds an on/off chip to the Controls row. */
    void addToggle(int labelRes, BooleanSupplier get, Consumer<Boolean> set) {
        LinearLayout row = root.findViewById(R.id.quick_toggles);
        View chip = LayoutInflater.from(context).inflate(R.layout.quick_slot, row, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            lp.setMarginStart(Math.round(8 * context.getResources().getDisplayMetrics().density));
        }
        row.addView(chip, lp);
        ((TextView) chip.findViewById(R.id.slot_name)).setText(labelRes);
        TextView state = chip.findViewById(R.id.slot_state);
        Runnable refresh = () -> {
            boolean on = get.getAsBoolean();
            chip.setSelected(on);
            state.setText(on ? R.string.quick_on : R.string.quick_off);
        };
        chip.setOnClickListener(v -> {
            set.accept(!get.getAsBoolean());
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            refresh.run();
        });
        toggleRefreshers.add(refresh); // runs when the menu opens, once the game has loaded its settings
    }

    boolean isShowing() {
        return showing;
    }

    void show() {
        if (showing) {
            return;
        }
        showing = true;
        refresh();
        root.setVisibility(View.VISIBLE);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(140).withEndAction(null).start();
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        root.animate().alpha(0f).setDuration(120).withEndAction(() -> root.setVisibility(View.GONE)).start();
    }

    private void refresh() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            View chip = slotViews[i];
            boolean saved = listener.hasSaveState(i + 1);
            chip.setSelected(selectedSlot == i + 1);
            ((TextView) chip.findViewById(R.id.slot_state))
                    .setText(saved ? R.string.quick_slot_saved : R.string.quick_slot_empty);
        }
        boolean canLoad = listener.hasSaveState(selectedSlot);
        loadButton.setEnabled(canLoad);
        loadButton.setAlpha(canLoad ? 1f : 0.4f);
        for (Runnable r : toggleRefreshers) {
            r.run();
        }
        toggleControls.setText(listener.areControlsHidden() ? R.string.quick_show_controls
                                                            : R.string.quick_hide_controls);
    }
}
