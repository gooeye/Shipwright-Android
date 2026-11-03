#include "TouchScroll.h"

#define IMGUI_DEFINE_MATH_OPERATORS
#include <imgui.h>
#include <imgui_internal.h>
#include <cmath>

namespace TouchScroll {
namespace {

enum class Gesture { None, Undecided, Widget, Scrolling };

Gesture sGesture = Gesture::None;
ImVec2 sStart;
ImGuiID sPressWindow = 0; // window under the finger when it went down
ImGuiID sTarget = 0;      // window being scrolled
bool sVertical = true;
float sVelocity = 0.0f; // px per second, carried on after the finger lifts
ImGuiID sFling = 0;
bool sFlingVertical = true;

// First window from `window` outwards that can scroll on the given axis. Stops at popups so a
// swipe in a dropdown never scrolls the menu behind it.
ImGuiWindow* FindScrollable(ImGuiWindow* window, bool vertical) {
    for (; window != nullptr; window = window->ParentWindow) {
        float max = vertical ? window->ScrollMax.y : window->ScrollMax.x;
        bool locked = (window->Flags & (ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse)) != 0;
        if (max > 0.0f && !locked) {
            return window;
        }
        if (window->Flags & (ImGuiWindowFlags_Popup | ImGuiWindowFlags_Tooltip)) {
            break;
        }
    }
    return nullptr;
}

// Returns false once the window can't scroll any further that way.
bool ScrollBy(ImGuiWindow* window, bool vertical, float amount) {
    if (vertical) {
        float target = ImClamp(window->Scroll.y + amount, 0.0f, window->ScrollMax.y);
        ImGui::SetScrollY(window, target);
        return target > 0.0f && target < window->ScrollMax.y;
    }
    float target = ImClamp(window->Scroll.x + amount, 0.0f, window->ScrollMax.x);
    ImGui::SetScrollX(window, target);
    return target > 0.0f && target < window->ScrollMax.x;
}

} // namespace

void Update() {
    ImGuiContext& g = *GImGui;
    ImGuiIO& io = g.IO;
    const float dt = io.DeltaTime > 0.0f ? io.DeltaTime : 1.0f / 60.0f;
    const float slop = g.FontSize * 0.5f; // movement before a touch counts as a drag

    if (io.MouseClicked[0]) {
        sFling = 0;
        sVelocity = 0.0f;
        sStart = io.MousePos;
        sPressWindow = g.HoveredWindow != nullptr ? g.HoveredWindow->ID : 0;
        sGesture = io.MouseSource == ImGuiMouseSource_TouchScreen ? Gesture::Undecided : Gesture::None;
    }

    if (!io.MouseDown[0]) {
        if (sGesture == Gesture::Scrolling && std::fabs(sVelocity) > 60.0f) {
            sFling = sTarget;
            sFlingVertical = sVertical;
        }
        sGesture = Gesture::None;
    } else if (sGesture == Gesture::Undecided) {
        ImVec2 moved = io.MousePos - sStart;
        if (std::fabs(moved.x) > slop || std::fabs(moved.y) > slop) {
            bool vertical = std::fabs(moved.y) >= std::fabs(moved.x);
            ImGuiWindow* target = FindScrollable(ImGui::FindWindowByID(sPressWindow), vertical);
            if (target != nullptr) {
                sGesture = Gesture::Scrolling;
                sTarget = target->ID;
                sVertical = vertical;
                // Whatever the finger landed on (button, checkbox, slider) must not react now.
                ImGui::ClearActiveID();
            } else {
                sGesture = Gesture::Widget; // e.g. a sideways drag on a slider
            }
        }
    }

    if (sGesture == Gesture::Scrolling) {
        ImGuiWindow* window = ImGui::FindWindowByID(sTarget);
        if (window == nullptr) {
            sGesture = Gesture::None;
            return;
        }
        float delta = -(sVertical ? io.MouseDelta.y : io.MouseDelta.x);
        ScrollBy(window, sVertical, delta);
        sVelocity = sVelocity * 0.6f + (delta / dt) * 0.4f;
        if (g.ActiveId != 0) {
            ImGui::ClearActiveID();
        }
    } else if (sFling != 0) {
        ImGuiWindow* window = ImGui::FindWindowByID(sFling);
        if (window == nullptr || std::fabs(sVelocity) < 30.0f || !ScrollBy(window, sFlingVertical, sVelocity * dt)) {
            sFling = 0;
            return;
        }
        sVelocity *= std::pow(0.04f, dt); // keeps 4% of its speed after one second
    }
}

bool IsGestureUndecided() {
    return sGesture == Gesture::Undecided || sGesture == Gesture::Scrolling;
}

} // namespace TouchScroll
