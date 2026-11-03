package com.dishii.soh;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-screen controller: button and stick bindings, drag-to-look, and the player's layout
 * (position, size and visibility of each control, plus overall opacity), with an editor to
 * change it. UI thread only.
 */
final class TouchControls {

    /** Native input calls, implemented by MainActivity. */
    interface Input {
        void setButton(int button, boolean value);
        void setAxis(int axis, short value);
        void addLookDelta(float dxDp, float dyDp);
        /** Whether the on-screen controls are showing (then the camera ignores the right stick). */
        void setControlsVisible(boolean visible);
    }

    /** A control the player can move and resize as one piece (e.g. the four C buttons). */
    private static final class Group {
        final String key;
        final int labelRes;
        final View[] views;
        float offsetX; // fraction of the overlay width
        float offsetY; // fraction of the overlay height
        float scale = 1f;
        boolean hidden;
        final RectF base = new RectF(); // untransformed bounds, overlay coordinates

        Group(String key, int labelRes, View... views) {
            this.key = key;
            this.labelRes = labelRes;
            this.views = views;
        }
    }

    private static final String PREFS_PREFIX = "controls.";
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;
    private static final float MIN_OPACITY = 0.2f;
    private static final float JOYSTICK_IDLE_ALPHA = 0.75f;
    private static final OvershootInterpolator RELEASE_INTERPOLATOR = new OvershootInterpolator(3f);

    private final Context context;
    private final Input input;
    private final SharedPreferences prefs;
    private final ViewGroup root;
    private final ViewGroup buttonGroup;
    private final View joystick;
    private final View toggleButton;
    private final float density;

    private final List<Group> groups = new ArrayList<>();
    private final Map<View, Group> groupOf = new HashMap<>();
    private final List<Integer> boundButtons = new ArrayList<>();
    private float opacity;
    private boolean controlsHidden; // the eye button
    private boolean suspended; // hidden while the game's own menu is open
    private Editor editor;

    @SuppressLint("InflateParams")
    TouchControls(Context context, ViewGroup parent, SharedPreferences prefs, Input input, View.OnClickListener onMenu) {
        this.context = context;
        this.input = input;
        this.prefs = prefs;
        this.density = context.getResources().getDisplayMetrics().density;

        root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.touchcontrol_overlay, null);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        parent.addView(root);
        buttonGroup = root.findViewById(R.id.button_group);
        joystick = root.findViewById(R.id.left_joystick);
        toggleButton = root.findViewById(R.id.buttonToggle);

        bindButton(R.id.buttonA, ControllerButtons.BUTTON_A);
        bindButton(R.id.buttonB, ControllerButtons.BUTTON_B);
        bindButton(R.id.buttonX, ControllerButtons.BUTTON_X);
        bindButton(R.id.buttonY, ControllerButtons.BUTTON_Y);
        bindCButton(R.id.buttonDpadUp, ControllerButtons.AXIS_RY, 1);
        bindCButton(R.id.buttonDpadDown, ControllerButtons.AXIS_RY, -1);
        bindCButton(R.id.buttonDpadLeft, ControllerButtons.AXIS_RX, 1);
        bindCButton(R.id.buttonDpadRight, ControllerButtons.AXIS_RX, -1);
        bindButton(R.id.buttonLB, ControllerButtons.BUTTON_LB);
        bindButton(R.id.buttonRB, ControllerButtons.BUTTON_RB);
        bindButton(R.id.buttonZ, ControllerButtons.AXIS_RT);
        bindButton(R.id.buttonStart, ControllerButtons.BUTTON_START);
        setupJoystick((FrameLayout) joystick, root.findViewById(R.id.left_joystick_knob));
        setupLookArea(root.findViewById(R.id.right_screen_area));

        View menuButton = root.findViewById(R.id.buttonMenu);
        menuButton.setOnClickListener(onMenu);
        menuButton.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressFeedback(v, true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressFeedback(v, false);
            }
            return false; // let the click listener run
        });
        toggleButton.setOnClickListener(v -> {
            setControlsHidden(!controlsHidden);
            haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
        });

        addGroup(new Group("stick", R.string.control_stick, joystick));
        addGroup(new Group("c", R.string.control_c, root.findViewById(R.id.imageView),
                root.findViewById(R.id.buttonDpadUp), root.findViewById(R.id.buttonDpadDown),
                root.findViewById(R.id.buttonDpadLeft), root.findViewById(R.id.buttonDpadRight)));
        addGroup(new Group("a", R.string.control_a, root.findViewById(R.id.buttonA)));
        addGroup(new Group("b", R.string.control_b, root.findViewById(R.id.buttonB)));
        addGroup(new Group("x", R.string.control_x, root.findViewById(R.id.buttonX)));
        addGroup(new Group("y", R.string.control_y, root.findViewById(R.id.buttonY)));
        addGroup(new Group("l", R.string.control_l, root.findViewById(R.id.buttonLB)));
        addGroup(new Group("r", R.string.control_r, root.findViewById(R.id.buttonRB)));
        addGroup(new Group("z", R.string.control_z, root.findViewById(R.id.buttonZ)));
        addGroup(new Group("start", R.string.control_start, root.findViewById(R.id.buttonStart)));
        addGroup(new Group("menu", R.string.control_menu, menuButton));
        addGroup(new Group("toggle", R.string.control_toggle, toggleButton));

        loadLayout();
        // "controlsVisible" actually stored "hidden"; key kept so existing installs keep their choice.
        controlsHidden = prefs.getBoolean("controlsVisible", false);
        buttonGroup.setVisibility(controlsHidden ? View.INVISIBLE : View.VISIBLE);
        input.setControlsVisible(!controlsHidden);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyLayout());
    }

    boolean isVibrationOn() {
        return prefs.getBoolean("haptics", true);
    }

    void setVibrationOn(boolean on) {
        prefs.edit().putBoolean("haptics", on).apply();
    }

    private void haptic(View v, int type) {
        if (isVibrationOn()) {
            v.performHapticFeedback(type);
        }
    }

    View getView() {
        return root;
    }

    boolean areControlsHidden() {
        return controlsHidden;
    }

    boolean isEditing() {
        return editor != null;
    }

    /** Hides everything while the game's own menu is open so touches reach it. */
    void setSuspended(boolean suspended) {
        if (this.suspended == suspended) {
            return;
        }
        this.suspended = suspended;
        if (suspended) {
            releaseAll();
        }
        root.animate().cancel();
        if (suspended) {
            root.animate().alpha(0f).setDuration(120).withEndAction(() -> root.setVisibility(View.GONE)).start();
        } else {
            root.setVisibility(View.VISIBLE);
            root.animate().alpha(1f).setDuration(150).withEndAction(null).start();
        }
    }

    void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        input.setControlsVisible(!hidden);
        if (hidden) {
            releaseAll();
        }
        buttonGroup.animate().cancel();
        if (hidden) {
            buttonGroup.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> buttonGroup.setVisibility(View.INVISIBLE)).start();
        } else {
            buttonGroup.setAlpha(0f);
            buttonGroup.setVisibility(View.VISIBLE);
            buttonGroup.animate().alpha(1f).setDuration(150).withEndAction(null).start();
        }
        toggleButton.animate().alpha(hidden ? 0.45f * opacity : opacity).setDuration(150).start();
        prefs.edit().putBoolean("controlsVisible", hidden).apply();
    }

    /** Lets go of every button and centers the sticks, e.g. before pausing. */
    void releaseAll() {
        for (int button : boundButtons) {
            input.setButton(button, false);
        }
        input.setAxis(ControllerButtons.AXIS_LX, (short) 0);
        input.setAxis(ControllerButtons.AXIS_LY, (short) 0);
        input.setAxis(ControllerButtons.AXIS_RX, (short) 0);
        input.setAxis(ControllerButtons.AXIS_RY, (short) 0);
        for (Group group : groups) {
            for (View v : group.views) {
                if (v.isPressed()) {
                    pressFeedback(v, false);
                }
            }
        }
        View knob = root.findViewById(R.id.left_joystick_knob);
        knob.setPressed(false);
        knob.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(150).start();
        joystick.setAlpha(JOYSTICK_IDLE_ALPHA * opacity);
    }

    // ---------------------------------------------------------------------------------------
    // Bindings
    // ---------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void bindButton(int viewId, int buttonNum) {
        boundButtons.add(buttonNum);
        root.findViewById(viewId).setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    input.setButton(buttonNum, true);
                    pressFeedback(v, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    input.setButton(buttonNum, false);
                    pressFeedback(v, false);
                    break;
            }
            return true;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindCButton(int viewId, int axis, int direction) {
        root.findViewById(viewId).setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    input.setAxis(axis, direction < 0 ? Short.MAX_VALUE : Short.MIN_VALUE);
                    pressFeedback(v, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    input.setAxis(axis, (short) 0);
                    pressFeedback(v, false);
                    break;
            }
            return true;
        });
    }

    /** Visual "squish", highlight and a light haptic tick for a pressed control. */
    private void pressFeedback(View v, boolean down) {
        Group group = groupOf.get(v);
        float scale = group != null ? group.scale : 1f;
        v.setPressed(down);
        v.animate().cancel();
        if (down) {
            v.animate().scaleX(scale * 0.88f).scaleY(scale * 0.88f).setDuration(60).setInterpolator(null).start();
            haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
        } else {
            v.animate().scaleX(scale).scaleY(scale).setDuration(180).setInterpolator(RELEASE_INTERPOLATOR).start();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                haptic(v, HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
            }
        }
    }

    /** Dragging on empty screen turns the camera (third person) or aims (first person). */
    @SuppressLint("ClickableViewAccessibility")
    private void setupLookArea(View area) {
        area.setOnTouchListener(new View.OnTouchListener() {
            private int pointerId = -1;
            private float lastX;
            private float lastY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointerId = event.getPointerId(0);
                        lastX = event.getX();
                        lastY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        int index = event.findPointerIndex(pointerId);
                        if (index < 0) {
                            break;
                        }
                        float x = event.getX(index);
                        float y = event.getY(index);
                        input.addLookDelta((x - lastX) / density, (y - lastY) / density);
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case MotionEvent.ACTION_POINTER_UP: {
                        // If the tracked finger lifts, carry on with another one still down.
                        int index = event.getActionIndex();
                        if (event.getPointerId(index) == pointerId) {
                            int next = index == 0 ? 1 : 0;
                            pointerId = event.getPointerId(next);
                            lastX = event.getX(next);
                            lastY = event.getY(next);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pointerId = -1;
                        break;
                }
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupJoystick(FrameLayout base, ImageView knob) {
        base.setOnTouchListener(new View.OnTouchListener() {
            private boolean atEdge;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float centerX = base.getWidth() / 2f;
                float centerY = base.getHeight() / 2f;
                float maxRadius = base.getWidth() / 2f - knob.getWidth() / 2f;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        base.animate().cancel();
                        base.animate().alpha(opacity).setDuration(80).start();
                        knob.animate().cancel();
                        knob.setPressed(true);
                        knob.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).setInterpolator(null).start();
                        haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                        atEdge = false;
                        // fall through to position the knob
                    case MotionEvent.ACTION_MOVE: {
                        float deltaX = event.getX() - centerX;
                        float deltaY = event.getY() - centerY;
                        float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                        if (distance > maxRadius && distance > 0) {
                            float scale = maxRadius / distance;
                            deltaX *= scale;
                            deltaY *= scale;
                        }
                        // A light tick when the stick reaches full tilt, like hitting the gate on a real stick.
                        boolean edge = distance >= maxRadius * 0.98f;
                        if (edge && !atEdge) {
                            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
                        }
                        atEdge = edge;

                        knob.setTranslationX(deltaX);
                        knob.setTranslationY(deltaY);

                        if (maxRadius > 0) {
                            input.setAxis(ControllerButtons.AXIS_LX, (short) (deltaX / maxRadius * Short.MAX_VALUE));
                            input.setAxis(ControllerButtons.AXIS_LY, (short) (deltaY / maxRadius * Short.MAX_VALUE));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        input.setAxis(ControllerButtons.AXIS_LX, (short) 0);
                        input.setAxis(ControllerButtons.AXIS_LY, (short) 0);
                        knob.setPressed(false);
                        knob.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                                .setDuration(220).setInterpolator(RELEASE_INTERPOLATOR).start();
                        base.animate().alpha(JOYSTICK_IDLE_ALPHA * opacity).setDuration(200).start();
                        return true;
                }
                return true;
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    private void addGroup(Group group) {
        groups.add(group);
        for (View v : group.views) {
            groupOf.put(v, group);
        }
    }

    private void loadLayout() {
        opacity = prefs.getFloat(PREFS_PREFIX + "opacity", 1f);
        for (Group g : groups) {
            String k = PREFS_PREFIX + g.key + ".";
            g.offsetX = prefs.getFloat(k + "x", 0f);
            g.offsetY = prefs.getFloat(k + "y", 0f);
            g.scale = clamp(prefs.getFloat(k + "scale", 1f), MIN_SCALE, MAX_SCALE);
            g.hidden = prefs.getBoolean(k + "hidden", false);
        }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = prefs.edit();
        e.putFloat(PREFS_PREFIX + "opacity", opacity);
        for (Group g : groups) {
            String k = PREFS_PREFIX + g.key + ".";
            e.putFloat(k + "x", g.offsetX);
            e.putFloat(k + "y", g.offsetY);
            e.putFloat(k + "scale", g.scale);
            e.putBoolean(k + "hidden", g.hidden);
        }
        e.apply();
    }

    private void resetLayout() {
        opacity = 1f;
        for (Group g : groups) {
            g.offsetX = 0f;
            g.offsetY = 0f;
            g.scale = 1f;
            g.hidden = false;
        }
        applyLayout();
    }

    /** Position of a view's untransformed top-left corner in overlay coordinates. */
    private void positionInRoot(View v, float[] out) {
        float x = v.getLeft();
        float y = v.getTop();
        ViewParent p = v.getParent();
        while (p instanceof View && p != root) {
            x += ((View) p).getLeft();
            y += ((View) p).getTop();
            p = p.getParent();
        }
        out[0] = x;
        out[1] = y;
    }

    private void updateBase(Group g) {
        float[] pos = new float[2];
        g.base.setEmpty();
        for (View v : g.views) {
            positionInRoot(v, pos);
            RectF r = new RectF(pos[0], pos[1], pos[0] + v.getWidth(), pos[1] + v.getHeight());
            if (g.base.isEmpty()) {
                g.base.set(r);
            } else {
                g.base.union(r);
            }
        }
    }

    /** Where the group currently appears on screen. */
    private RectF currentBounds(Group g) {
        float cx = g.base.centerX() + g.offsetX * root.getWidth();
        float cy = g.base.centerY() + g.offsetY * root.getHeight();
        float hw = g.base.width() * g.scale / 2f;
        float hh = g.base.height() * g.scale / 2f;
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void applyLayout() {
        if (root.getWidth() == 0) {
            return;
        }
        for (Group g : groups) {
            applyGroup(g);
        }
    }

    private void applyGroup(Group g) {
        updateBase(g);
        float[] pos = new float[2];
        float cx = g.base.centerX();
        float cy = g.base.centerY();
        float ox = g.offsetX * root.getWidth();
        float oy = g.offsetY * root.getHeight();
        for (View v : g.views) {
            positionInRoot(v, pos);
            float vx = pos[0] + v.getWidth() / 2f;
            float vy = pos[1] + v.getHeight() / 2f;
            // Scale the whole group around its center, then move it.
            v.setTranslationX(cx + (vx - cx) * g.scale + ox - vx);
            v.setTranslationY(cy + (vy - cy) * g.scale + oy - vy);
            v.setScaleX(g.scale);
            v.setScaleY(g.scale);

            boolean visible = !g.hidden || editor != null;
            v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            float alpha = opacity;
            if (g.hidden) {
                alpha = 0.3f; // only reached in the editor
            } else if (v == joystick) {
                alpha = JOYSTICK_IDLE_ALPHA * opacity;
            } else if (v == toggleButton && controlsHidden) {
                alpha = 0.45f * opacity;
            }
            v.setAlpha(alpha);
        }
    }

    private void clampIntoScreen(Group g) {
        RectF r = currentBounds(g);
        float w = root.getWidth();
        float h = root.getHeight();
        float dx = 0f;
        float dy = 0f;
        if (r.width() <= w) {
            if (r.left < 0) dx = -r.left;
            else if (r.right > w) dx = w - r.right;
        }
        if (r.height() <= h) {
            if (r.top < 0) dy = -r.top;
            else if (r.bottom > h) dy = h - r.bottom;
        }
        g.offsetX += dx / w;
        g.offsetY += dy / h;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------------------------------
    // Layout editor
    // ---------------------------------------------------------------------------------------

    /** Opens the editor over the controls; onDone runs after the player taps Done. */
    void startEditing(Runnable onDone) {
        if (editor != null) {
            return;
        }
        releaseAll();
        editor = new Editor(onDone);
        buttonGroup.animate().cancel();
        buttonGroup.setAlpha(1f);
        buttonGroup.setVisibility(View.VISIBLE);
        applyLayout();
    }

    /** Saves and closes the editor (also used for the back button). */
    void finishEditing() {
        if (editor == null) {
            return;
        }
        Editor closing = editor;
        editor = null;
        ((ViewGroup) closing.layer.getParent()).removeView(closing.layer);
        saveLayout();
        applyLayout();
        buttonGroup.setVisibility(controlsHidden ? View.INVISIBLE : View.VISIBLE);
        if (closing.onDone != null) {
            closing.onDone.run();
        }
    }

    private final class Editor {
        final Runnable onDone;
        final FrameLayout layer;
        final EditorCanvas canvas;
        final View toolbar;
        final TextView title;
        final TextView sizeLabel;
        final SeekBar size;
        final Button hide;
        final TextView opacityLabel;
        final SeekBar opacitySeek;
        Group selected;
        private boolean updatingUi;

        @SuppressLint("InflateParams")
        Editor(Runnable onDone) {
            this.onDone = onDone;
            layer = new FrameLayout(context);
            canvas = new EditorCanvas(context, this);
            layer.addView(canvas, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            toolbar = LayoutInflater.from(context).inflate(R.layout.controls_editor, null);
            layer.addView(toolbar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            // Sibling of the overlay (not a child) so it shares the overlay's coordinates; the
            // overlay itself is padded for display cutouts.
            ((ViewGroup) root.getParent()).addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            title = toolbar.findViewById(R.id.editor_title);
            sizeLabel = toolbar.findViewById(R.id.editor_size_label);
            size = toolbar.findViewById(R.id.editor_size);
            hide = toolbar.findViewById(R.id.editor_hide);
            opacityLabel = toolbar.findViewById(R.id.editor_opacity_label);
            opacitySeek = toolbar.findViewById(R.id.editor_opacity);

            size.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updatingUi || selected == null) {
                        return;
                    }
                    setScale(selected, MIN_SCALE + progress / 100f);
                }
            });
            opacitySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updatingUi) {
                        return;
                    }
                    opacity = MIN_OPACITY + progress / 100f;
                    applyLayout();
                    updateUi();
                }
            });
            hide.setOnClickListener(v -> {
                if (selected != null) {
                    selected.hidden = !selected.hidden;
                    applyGroup(selected);
                    updateUi();
                    canvas.invalidate();
                }
            });
            toolbar.findViewById(R.id.editor_reset).setOnClickListener(v -> {
                resetLayout();
                select(null);
            });
            toolbar.findViewById(R.id.editor_done).setOnClickListener(v -> finishEditing());
            updateUi();
        }

        void select(Group group) {
            selected = group;
            updateUi();
            placeToolbar();
            canvas.invalidate();
        }

        void setScale(Group g, float scale) {
            g.scale = clamp(scale, MIN_SCALE, MAX_SCALE);
            clampIntoScreen(g);
            applyGroup(g);
            updateUi();
            canvas.invalidate();
        }

        void updateUi() {
            updatingUi = true;
            boolean has = selected != null;
            if (has) {
                String name = context.getString(selected.labelRes);
                title.setText(selected.hidden ? context.getString(R.string.editor_hidden_suffix, name) : name);
            } else {
                title.setText(R.string.editor_hint);
            }
            size.setEnabled(has);
            hide.setEnabled(has);
            hide.setAlpha(has ? 1f : 0.4f);
            hide.setText(has && selected.hidden ? R.string.editor_show : R.string.editor_hide);
            int scalePercent = has ? Math.round(selected.scale * 100) : 100;
            size.setProgress(scalePercent - Math.round(MIN_SCALE * 100));
            sizeLabel.setText(context.getString(R.string.editor_size, scalePercent));
            opacitySeek.setProgress(Math.round((opacity - MIN_OPACITY) * 100));
            opacityLabel.setText(context.getString(R.string.editor_opacity, Math.round(opacity * 100)));
            updatingUi = false;
        }

        /** Keeps the toolbar clear of the control being edited. */
        void placeToolbar() {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
            int gravity = Gravity.CENTER;
            if (selected != null) {
                RectF r = currentBounds(selected);
                gravity = Gravity.CENTER_HORIZONTAL
                        | (r.centerY() < root.getHeight() / 2f ? Gravity.BOTTOM : Gravity.TOP);
            }
            if (lp.gravity != gravity) {
                lp.gravity = gravity;
                int margin = Math.round(12 * density);
                lp.topMargin = margin;
                lp.bottomMargin = margin;
                toolbar.setLayoutParams(lp);
            }
        }
    }

    /** Draws an outline around every control and handles drag / pinch while editing. */
    @SuppressLint("ViewConstructor")
    private final class EditorCanvas extends View {
        private final Editor editor;
        private final Paint scrim = new Paint();
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector pinch;
        private float lastX;
        private float lastY;
        private boolean dragging;

        EditorCanvas(Context context, Editor editor) {
            super(context);
            this.editor = editor;
            scrim.setColor(0x73000000);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(1.5f * density);
            outline.setColor(0x99FFFFFF);
            outline.setPathEffect(new DashPathEffect(new float[] { 6 * density, 4 * density }, 0));
            selectedOutline.setStyle(Paint.Style.STROKE);
            selectedOutline.setStrokeWidth(2.5f * density);
            selectedOutline.setColor(context.getColor(R.color.setup_accent));
            selectedFill.setColor(context.getColor(R.color.setup_accent_dim));
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (editor.selected != null) {
                        editor.setScale(editor.selected, editor.selected.scale * detector.getScaleFactor());
                    }
                    return true;
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), scrim);
            float pad = 4 * density;
            float radius = 10 * density;
            for (Group g : groups) {
                RectF r = currentBounds(g);
                r.inset(-pad, -pad);
                if (g == editor.selected) {
                    c.drawRoundRect(r, radius, radius, selectedFill);
                    c.drawRoundRect(r, radius, radius, selectedOutline);
                } else {
                    c.drawRoundRect(r, radius, radius, outline);
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    Group hit = hitTest(event.getX(), event.getY());
                    editor.select(hit);
                    dragging = hit != null;
                    lastX = event.getX();
                    lastY = event.getY();
                    if (hit != null) {
                        haptic(this, HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    dragging = false; // two fingers: resize only
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && !pinch.isInProgress() && editor.selected != null) {
                        Group g = editor.selected;
                        g.offsetX += (event.getX() - lastX) / root.getWidth();
                        g.offsetY += (event.getY() - lastY) / root.getHeight();
                        clampIntoScreen(g);
                        applyGroup(g);
                        invalidate();
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    editor.placeToolbar();
                    break;
            }
            return true;
        }

        private Group hitTest(float x, float y) {
            float slop = 10 * density;
            // Last added is drawn on top, so test it first.
            for (int i = groups.size() - 1; i >= 0; i--) {
                RectF r = currentBounds(groups.get(i));
                r.inset(-slop, -slop);
                if (r.contains(x, y)) {
                    return groups.get(i);
                }
            }
            return null;
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
