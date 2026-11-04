// Android glue for the on-screen controls: drag-to-look camera, drag-to-aim in first person,
// and save state requests from the quick menu. Everything coming from the Android UI thread is
// queued here and applied on the game thread at the start of the next frame.
#ifdef __ANDROID__

#include <jni.h>
#include <string>
#include <chrono>
#include <condition_variable>
#include <SDL2/SDL.h>
#include <cmath>
#include <imgui.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "soh/OTRGlobals.h"
#include "soh/ShipInit.hpp"
#include "soh/Enhancements/game-interactor/GameInteractor.h"
#include "soh/Enhancements/savestates.h"
#include "z64player.h"
#include "global.h"

extern "C" PlayState* gPlayState;

namespace {

// Camera turn per dp of finger travel, in binary angle units (0x10000 = 360 degrees).
constexpr float kThirdPersonTurnPerDp = 100.0f;
constexpr float kFirstPersonTurnPerDp = 30.0f;

struct StateRequest {
    unsigned int slot;
    bool load;
};

struct StateLocation {
    s16 scene;
    s8 room;
};

std::mutex sMutex;
float sPendingX = 0.0f; // finger travel (dp) since the last frame, written by the UI thread
float sPendingY = 0.0f;
std::vector<StateRequest> sPendingStates;

float sFrameX = 0.0f; // finger travel applied during the current frame
float sFrameY = 0.0f;

// The touch C buttons drive the virtual right stick, which the camera would also read. While
// the touch controls are on screen the camera follows finger drags only.
std::atomic<bool> sTouchControlsVisible{ true };

s32 sFramesSinceDrag = 1000; // game frames since the finger last moved on the look area

// Pause menu: the game thread waits at the start of the next frame. Pausing this way (rather than
// through SDL's background pause) leaves the window and fullscreen state alone.
std::atomic<bool> sMenuPaused{ false };
std::mutex sPauseMutex;
std::condition_variable sPauseCv;

// Where each slot was saved. States only restore safely in the same scene and room.
// Guarded by sMutex (the pause menu reads it from the UI thread).
std::unordered_map<unsigned int, StateLocation> sStateLocations;

void Notify(const char* text) {
    Ship::Context::GetInstance()->GetWindow()->GetGui()->GetGameOverlay()->TextDrawNotification(2.0f, true, "%s",
                                                                                                 text);
}

void HandleStateRequest(const StateRequest& request) {
    if (gPlayState == nullptr) {
        Notify("Save states only work during gameplay");
        return;
    }
    StateLocation here = { gPlayState->sceneNum, gPlayState->roomCtx.curRoom.num };

    if (!request.load) {
        if (OTRGlobals::Instance->gSaveStateMgr->AddRequest({ request.slot, RequestType::SAVE }) ==
            SaveStateReturn::SUCCESS) {
            std::lock_guard<std::mutex> lock(sMutex);
            sStateLocations[request.slot] = here;
        }
        return;
    }

    StateLocation saved;
    {
        std::lock_guard<std::mutex> lock(sMutex);
        auto found = sStateLocations.find(request.slot);
        if (found == sStateLocations.end()) {
            Notify("That slot is empty");
            return;
        }
        saved = found->second;
    }
    if (saved.scene != here.scene || saved.room != here.room) {
        // Loading a state from another area corrupts the game, so refuse instead.
        Notify("That state was saved in another area. Go back there to load it.");
        return;
    }
    OTRGlobals::Instance->gSaveStateMgr->AddRequest({ request.slot, RequestType::LOAD });
}

void HandleFirstPersonAim(Player* player) {
    if (sFrameX == 0.0f && sFrameY == 0.0f) {
        return;
    }
    f32 xMulti = CVarGetFloat(CVAR_SETTING("FirstPersonCameraSensitivity.X"), 1.0f);
    f32 yMulti = CVarGetFloat(CVAR_SETTING("FirstPersonCameraSensitivity.Y"), 1.0f);
    bool invertX = CVarGetInteger(CVAR_SETTING("Controls.InvertAimingXAxis"), 0) !=
                   CVarGetInteger(CVAR_ENHANCEMENT("MirroredWorld"), 0);
    bool invertY = CVarGetInteger(CVAR_SETTING("Controls.InvertAimingYAxis"), 1);
    player->actor.focus.rot.y -= (s16)(sFrameX * kFirstPersonTurnPerDp * xMulti * (invertX ? -1 : 1));
    player->actor.focus.rot.x += (s16)(sFrameY * kFirstPersonTurnPerDp * yMulti * (invertY ? 1 : -1));
}

void RegisterMobileHooks() {
    // Touch-friendly defaults, applied once; a player who changes them keeps their choice.
    // In first person the stick walks and dragging aims, and the view stays where it's aimed.
    // (Drag-to-look in third person has its own setting, see Camera_FreeLookEnabled.)
    bool changed = false;
    for (const char* name : { CVAR_SETTING("MoveInFirstPerson"), CVAR_SETTING("DisableFirstPersonAutoCenterView") }) {
        if (CVarGetInteger(name, -1) == -1) {
            CVarSetInteger(name, 1);
            changed = true;
        }
    }
    if (changed) {
        CVarSave();
    }
    COND_HOOK(OnPlayerFirstPersonControl, true, HandleFirstPersonAim);
}

RegisterShipInitFunc sInitMobileHooks(RegisterMobileHooks);

} // namespace

extern "C" {

// Game thread, once per frame before input is read.
void Mobile_StartFrame() {
    while (sMenuPaused) {
        // Keep SDL's event handling alive so going to the background and back still works.
        SDL_PumpEvents();
        std::unique_lock<std::mutex> lock(sPauseMutex);
        sPauseCv.wait_for(lock, std::chrono::milliseconds(50), [] { return !sMenuPaused; });
    }
    std::vector<StateRequest> states;
    {
        std::lock_guard<std::mutex> lock(sMutex);
        sFrameX = sPendingX;
        sFrameY = sPendingY;
        sFramesSinceDrag = (sFrameX != 0.0f || sFrameY != 0.0f) ? 0 : std::min(sFramesSinceDrag + 1, 1000);
        sPendingX = 0.0f;
        sPendingY = 0.0f;
        states.swap(sPendingStates);
    }
    for (const auto& request : states) {
        HandleStateRequest(request);
    }
}

// Camera debug readout, drawn every frame from SohGui/Menu.cpp when enabled.
extern const char* gTouchDbgCameraFunc;
extern s32 gTouchDbgManualChecks;
extern s32 gTouchDbgManualCamera;
extern f32 gTouchDbgCamYaw;
s32 Camera_FreeLookEnabled(void);
static float sDbgDragTotal = 0.0f;
static s32 sDbgDragEvents = 0;

void TouchLook_DrawDebug() {
    if (!CVarGetInteger(CVAR_SETTING("Mobile.CameraDebug"), 1)) {
        return;
    }
    static s32 checks = 0;
    static s32 lastFrameChecks = 0;
    checks = gTouchDbgManualChecks;
    gTouchDbgManualChecks = 0;
    lastFrameChecks = checks;
    const ImGuiViewport* viewport = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(ImVec2(viewport->WorkPos.x + viewport->WorkSize.x * 0.5f, viewport->WorkPos.y + viewport->WorkSize.y * 0.22f),
                            ImGuiCond_Always, ImVec2(0.5f, 0.0f));
    ImGui::SetNextWindowBgAlpha(0.6f);
    ImGui::Begin("Camera debug", nullptr,
                 ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoInputs |
                     ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav);
    ImGui::Text("Touch drags received: %d (total %.0f dp)", sDbgDragEvents, sDbgDragTotal);
    ImGui::Text("This frame: dx %.1f dy %.1f, frames since drag %d", sFrameX, sFrameY, sFramesSinceDrag);
    ImGui::Text("Touch controls showing: %d", (int)sTouchControlsVisible.load());
    ImGui::Text("Drag to look %d, Free Look %d, follows movement %d", CVarGetInteger(CVAR_SETTING("Mobile.DragToLook"), 1),
                CVarGetInteger(CVAR_SETTING("FreeLook.Enabled"), 0),
                CVarGetInteger(CVAR_SETTING("Mobile.CameraFollowsMovement"), 1));
    ImGui::Text("Free camera allowed %d, checks/frame %d, manual camera %d", Camera_FreeLookEnabled(), lastFrameChecks,
                gTouchDbgManualCamera);
    ImGui::Text("Camera function: %s, free camera yaw %.0f deg", gTouchDbgCameraFunc != nullptr ? gTouchDbgCameraFunc : "?",
                gTouchDbgCamYaw * 360.0f / 65536.0f);
    // What happened during the most recent drag, so one screenshot afterwards is enough.
    static s32 lastDragChecks = -1;
    static s32 lastDragManual = -1;
    static std::string lastDragFunc;
    static float yawAtDragStart = 0.0f;
    static float lastDragYawChange = 0.0f;
    static bool inDrag = false;
    bool dragging = sFrameX != 0.0f || sFrameY != 0.0f;
    if (dragging && !inDrag) {
        yawAtDragStart = gTouchDbgCamYaw;
    }
    if (dragging) {
        lastDragChecks = lastFrameChecks;
        lastDragManual = gTouchDbgManualCamera;
        lastDragFunc = gTouchDbgCameraFunc != nullptr ? gTouchDbgCameraFunc : "?";
        lastDragYawChange = (gTouchDbgCamYaw - yawAtDragStart) * 360.0f / 65536.0f;
    }
    if (sFramesSinceDrag < 1000) {
        inDrag = sFramesSinceDrag < 3;
    }
    ImGui::Separator();
    ImGui::Text("Last drag: checks/frame %d, manual camera %d, function %s, yaw change %.0f deg", lastDragChecks,
                lastDragManual, lastDragFunc.c_str(), lastDragYawChange);
    ImGui::Text("Frames since last drag: %d", sFramesSinceDrag);
    ImGui::End();
}

void TouchLook_GetFrameDelta(f32* dxDp, f32* dyDp) {
    *dxDp = sFrameX;
    *dyDp = sFrameY;
}

s32 TouchLook_FramesSinceDrag() {
    return sFramesSinceDrag;
}

// Adds this frame's drag to the third-person camera input (same scale as the right stick * 10).
// Called right after the right stick was read and before the mouse.
void TouchLook_ApplyThirdPerson(f32* newCamX, f32* newCamY) {
    // The right stick only turns the camera when the controller Free Look option is on (and the
    // touch C buttons, which also drive it, aren't showing); otherwise it's just the C buttons.
    if (sTouchControlsVisible || !CVarGetInteger(CVAR_SETTING("FreeLook.Enabled"), 0)) {
        *newCamX = 0.0f;
        *newCamY = 0.0f;
    }
    // With "Camera follows movement" on, drags turn the normal camera instead (Camera_TouchTurn).
    if (!CVarGetInteger(CVAR_SETTING("Mobile.CameraFollowsMovement"), 1)) {
        *newCamX -= sFrameX * kThirdPersonTurnPerDp;
        *newCamY += sFrameY * kThirdPersonTurnPerDp;
    }
}

JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_addLookDelta(JNIEnv* env, jobject obj, jfloat dxDp,
                                                                     jfloat dyDp) {
    std::lock_guard<std::mutex> lock(sMutex);
    sPendingX += dxDp;
    sPendingY += dyDp;
    sDbgDragEvents++;
    sDbgDragTotal += std::fabs(dxDp) + std::fabs(dyDp);
}

JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_nativeSetPaused(JNIEnv* env, jobject obj, jboolean paused) {
    {
        std::lock_guard<std::mutex> lock(sPauseMutex);
        sMenuPaused = paused;
    }
    sPauseCv.notify_all();
}

// Called from the settings menu (game thread).
void Mobile_RequestState(unsigned int slot, bool load) {
    std::lock_guard<std::mutex> lock(sMutex);
    sPendingStates.push_back({ slot, load });
}

static void CallActivity(const char* method) {
    JNIEnv* env = (JNIEnv*)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = env->GetObjectClass(activity);
    jmethodID id = env->GetMethodID(cls, method, "()V");
    if (id != nullptr) {
        env->CallVoidMethod(activity, id);
    } else {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
}

void Mobile_OpenControlsEditor() {
    CallActivity("openControlsEditorFromGame");
}

void Mobile_ToggleTouchControls() {
    CallActivity("toggleTouchControlsFromGame");
}

JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_nativeSaveState(JNIEnv* env, jobject obj, jint slot,
                                                                        jboolean load) {
    std::lock_guard<std::mutex> lock(sMutex);
    sPendingStates.push_back({ (unsigned int)slot, (bool)load });
}

JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_nativeSetTouchControlsVisible(JNIEnv* env, jobject obj,
                                                                                    jboolean visible) {
    sTouchControlsVisible = visible;
}

// Simple settings access for the pause menu (the game is paused while it's open).
JNIEXPORT jint JNICALL Java_com_dishii_soh_MainActivity_nativeGetSetting(JNIEnv* env, jobject obj, jstring name,
                                                                         jint defaultValue) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    jint value = CVarGetInteger(chars, defaultValue);
    env->ReleaseStringUTFChars(name, chars);
    return value;
}

JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_nativeSetSetting(JNIEnv* env, jobject obj, jstring name,
                                                                         jint value) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    CVarSetInteger(chars, value);
    CVarSave();
    env->ReleaseStringUTFChars(name, chars);
}

JNIEXPORT jboolean JNICALL Java_com_dishii_soh_MainActivity_nativeHasSaveState(JNIEnv* env, jobject obj, jint slot) {
    std::lock_guard<std::mutex> lock(sMutex);
    return sStateLocations.count((unsigned int)slot) != 0;
}

} // extern "C"

#endif // __ANDROID__
