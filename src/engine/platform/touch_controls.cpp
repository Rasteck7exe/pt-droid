#include "engine/platform/touch_controls.h"

#include <SDL3/SDL.h>
#include <imgui.h>

#include <algorithm>
#include <cfloat>
#include <cmath>

#include "engine/platform/input.h"

namespace pt {
namespace {

constexpr float kStickDeadZone = 0.12f;
constexpr float kHitScale = 1.25f;  // buttons take touches a little outside their drawn circle

ImU32 White(float alpha) { return IM_COL32(255, 255, 255, static_cast<int>(std::clamp(alpha, 0.0f, 1.0f) * 255.0f)); }
ImVec2 V(glm::vec2 v) { return ImVec2(v.x, v.y); }

}

void TouchControls::SetEnabled(bool enabled) {
    if (enabled == enabled_) {
        return;
    }
    enabled_ = enabled;
    fingers_.clear();
}

void TouchControls::SetViewSize(float width, float height) {
    if (width <= 0.0f || height <= 0.0f || (width == size_.x && height == size_.y && !layout_.buttons.empty())) {
        return;
    }
    size_ = glm::vec2(width, height);
    layout_ = MakeLayout();
}

TouchControls::Layout TouchControls::MakeLayout() const {
    // landscape phone layout in units of the screen height
    const float w = size_.x;
    const float h = size_.y;
    const float u = std::min(w, h);
    Layout l;
    l.stick_radius = 0.15f * u;
    l.left_home = glm::vec2(0.30f * u, h - 0.27f * u);
    l.right_home = glm::vec2(w - 0.82f * u, h - 0.30f * u);
    l.dpad = glm::vec2(0.24f * u, 0.43f * u);
    l.dpad_size = 0.075f * u;

    const glm::vec2 face(w - 0.25f * u, h - 0.33f * u);
    const float d = 0.125f * u;
    const float r = 0.072f * u;
    l.buttons.push_back({kRawCross, false, Shape::Cross, "", face + glm::vec2(0.0f, d), r});
    l.buttons.push_back({kRawCircle, false, Shape::Circle, "", face + glm::vec2(d, 0.0f), r});
    l.buttons.push_back({kRawSquare, false, Shape::Square, "", face + glm::vec2(-d, 0.0f), r});
    l.buttons.push_back({kRawTriangle, false, Shape::Triangle, "", face + glm::vec2(0.0f, -d), r});
    l.buttons.push_back({kRawR3, false, Shape::Label, "ZOOM", glm::vec2(w - 0.58f * u, h - 0.15f * u), 0.068f * u});
    l.buttons.push_back({kRawL3, false, Shape::Label, "L3", glm::vec2(0.64f * u, h - 0.11f * u), 0.05f * u});
    l.buttons.push_back({kRawL1, false, Shape::Label, "L1", glm::vec2(0.17f * u, 0.11f * u), 0.065f * u, true});
    l.buttons.push_back({kRawR1, false, Shape::Label, "R1", glm::vec2(w - 0.17f * u, 0.11f * u), 0.065f * u, true});
    l.buttons.push_back({kRawSelect, false, Shape::Label, "PAD", glm::vec2(w * 0.5f - 0.15f * u, 0.09f * u), 0.05f * u, true});
    l.buttons.push_back({kRawStart, false, Shape::Label, "OPTIONS", glm::vec2(w * 0.5f + 0.15f * u, 0.09f * u), 0.05f * u, true});
    l.buttons.push_back({0, true, Shape::Label, "PC", glm::vec2(w - 0.42f * u, 0.11f * u), 0.045f * u});
    return l;
}

void TouchControls::Release(uint64_t id) {
    fingers_.erase(std::remove_if(fingers_.begin(), fingers_.end(), [&](const Finger& f) { return f.id == id; }), fingers_.end());
}

void TouchControls::ProcessEvent(const SDL_Event& event) {
    if (!enabled_) {
        return;
    }
    if (event.type != SDL_EVENT_FINGER_DOWN && event.type != SDL_EVENT_FINGER_MOTION && event.type != SDL_EVENT_FINGER_UP &&
        event.type != SDL_EVENT_FINGER_CANCELED) {
        return;
    }
    if (layout_.buttons.empty()) {
        layout_ = MakeLayout();
    }
    const uint64_t id = event.tfinger.fingerID;
    const glm::vec2 at(event.tfinger.x * size_.x, event.tfinger.y * size_.y);
    if (event.type == SDL_EVENT_FINGER_UP || event.type == SDL_EVENT_FINGER_CANCELED) {
        Release(id);
        return;
    }
    if (event.type == SDL_EVENT_FINGER_MOTION) {
        for (Finger& finger : fingers_) {
            if (finger.id != id) continue;
            finger.now = at;
            if (finger.control == Control::LeftStick || finger.control == Control::RightStick) {
                // the stick's base follows a finger that goes past its edge
                const glm::vec2 offset = finger.now - finger.origin;
                const float length = glm::length(offset);
                if (length > layout_.stick_radius) {
                    finger.origin = finger.now - offset * (layout_.stick_radius / length);
                }
            }
        }
        return;
    }
    // a new finger: the control under it
    Release(id);
    Finger finger;
    finger.id = id;
    finger.origin = at;
    finger.now = at;
    for (size_t i = 0; i < layout_.buttons.size(); ++i) {
        const Button& button = layout_.buttons[i];
        if (glm::length(at - button.center) <= button.radius * kHitScale) {
            finger.control = Control::Button;
            finger.button = static_cast<int>(i);
            break;
        }
    }
    if (finger.control == Control::None) {
        const glm::vec2 offset = at - layout_.dpad;
        if (std::abs(offset.x) <= layout_.dpad_size * 2.3f && std::abs(offset.y) <= layout_.dpad_size * 2.3f) {
            finger.control = Control::DPad;
        } else {
            finger.control = at.x < size_.x * 0.5f ? Control::LeftStick : Control::RightStick;
            // one finger per stick
            for (const Finger& other : fingers_) {
                if (other.control == finger.control) {
                    finger.control = Control::None;
                }
            }
        }
    }
    if (finger.control == Control::None) {
        return;
    }
    if (finger.control == Control::Button || finger.control == Control::DPad) {
        pressed_edge_ = true;
        if (finger.control == Control::Button && layout_.buttons[finger.button].settings) {
            settings_edge_ = true;
        }
    }
    fingers_.push_back(finger);
}

uint32_t TouchControls::DPadBits(glm::vec2 offset) const {
    if (glm::length(offset) < layout_.dpad_size * 0.35f) {
        return 0;
    }
    // eight directions: a diagonal holds both of its neighbours
    const float angle = std::atan2(offset.y, offset.x);
    const int sector = static_cast<int>(std::lround(angle / (3.14159265f / 4.0f))) & 7;
    constexpr uint32_t kSectors[8] = {kRawRight, kRawRight | kRawDown, kRawDown, kRawDown | kRawLeft,
                                      kRawLeft,  kRawLeft | kRawUp,    kRawUp,   kRawUp | kRawRight};
    return kSectors[sector];
}

glm::vec2 TouchControls::StickValue(const Finger& finger) const {
    glm::vec2 value = (finger.now - finger.origin) / layout_.stick_radius;
    const float length = glm::length(value);
    if (length < kStickDeadZone) {
        return glm::vec2(0.0f);
    }
    const float scaled = std::min(1.0f, (length - kStickDeadZone) / (1.0f - kStickDeadZone));
    return value / length * scaled;
}

TouchControls::State TouchControls::Take() {
    State state;
    state.pressed = pressed_edge_;
    state.settings = settings_edge_;
    pressed_edge_ = settings_edge_ = false;
    if (!enabled_) {
        return state;
    }
    for (const Finger& finger : fingers_) {
        state.active = true;
        switch (finger.control) {
        case Control::Button: state.raw |= layout_.buttons[finger.button].raw; break;
        case Control::DPad: state.raw |= DPadBits(finger.now - layout_.dpad); break;
        case Control::LeftStick: {
            const glm::vec2 v = StickValue(finger);
            state.left = glm::vec2(v.x, -v.y);
            break;
        }
        case Control::RightStick: state.right = StickValue(finger); break;
        case Control::None: break;
        }
    }
    return state;
}

void TouchControls::Draw(ImDrawList* list) const {
    if (!enabled_ || !list || layout_.buttons.empty()) {
        return;
    }
    const float u = std::min(size_.x, size_.y);
    ImFont* font = ImGui::GetFont();
    const auto label = [&](glm::vec2 center, float size, const char* text, ImU32 color) {
        const ImVec2 extent = font->CalcTextSizeA(size, FLT_MAX, 0.0f, text);
        list->AddText(font, size, ImVec2(center.x - extent.x * 0.5f, center.y - extent.y * 0.5f), color, text);
    };

    // sticks: the base where the finger landed (or its resting place) and the knob under the finger
    const auto stick = [&](Control control, glm::vec2 home) {
        const Finger* held = nullptr;
        for (const Finger& finger : fingers_) {
            if (finger.control == control) held = &finger;
        }
        const glm::vec2 base = held ? held->origin : home;
        const glm::vec2 knob = held ? held->now : home;
        list->AddCircleFilled(V(base), layout_.stick_radius, White(held ? 0.12f : 0.06f), 48);
        list->AddCircle(V(base), layout_.stick_radius, White(held ? 0.45f : 0.22f), 48, 0.006f * u);
        list->AddCircleFilled(V(knob), layout_.stick_radius * 0.42f, White(held ? 0.45f : 0.20f), 32);
    };
    stick(Control::LeftStick, layout_.left_home);
    stick(Control::RightStick, layout_.right_home);

    // D-pad
    uint32_t dpad_held = 0;
    for (const Finger& finger : fingers_) {
        if (finger.control == Control::DPad) dpad_held |= DPadBits(finger.now - layout_.dpad);
    }
    const float s = layout_.dpad_size;
    const struct {
        uint32_t raw;
        glm::vec2 direction;
    } arms[4] = {{kRawUp, {0.0f, -1.0f}}, {kRawDown, {0.0f, 1.0f}}, {kRawLeft, {-1.0f, 0.0f}}, {kRawRight, {1.0f, 0.0f}}};
    for (const auto& arm : arms) {
        const glm::vec2 c = layout_.dpad + arm.direction * (s * 1.15f);
        const bool on = (dpad_held & arm.raw) != 0;
        list->AddRectFilled(V(c - glm::vec2(s * 0.55f)), V(c + glm::vec2(s * 0.55f)), White(on ? 0.45f : 0.16f), s * 0.2f);
        list->AddRect(V(c - glm::vec2(s * 0.55f)), V(c + glm::vec2(s * 0.55f)), White(0.45f), s * 0.2f, 0, 0.004f * u);
        // the arrow
        const glm::vec2 side(-arm.direction.y, arm.direction.x);
        const glm::vec2 tip = c + arm.direction * (s * 0.3f);
        list->AddTriangleFilled(V(tip), V(c - arm.direction * (s * 0.15f) + side * (s * 0.28f)),
                                V(c - arm.direction * (s * 0.15f) - side * (s * 0.28f)), White(0.8f));
    }

    // buttons
    for (size_t i = 0; i < layout_.buttons.size(); ++i) {
        const Button& button = layout_.buttons[i];
        bool on = false;
        for (const Finger& finger : fingers_) {
            on |= finger.control == Control::Button && finger.button == static_cast<int>(i);
        }
        const glm::vec2 c = button.center;
        const float r = button.radius;
        if (button.pill) {
            const glm::vec2 half(r * 1.6f, r * 0.75f);
            list->AddRectFilled(V(c - half), V(c + half), White(on ? 0.45f : 0.16f), r * 0.75f);
            list->AddRect(V(c - half), V(c + half), White(0.5f), r * 0.75f, 0, 0.004f * u);
        } else {
            list->AddCircleFilled(V(c), r, White(on ? 0.45f : 0.16f), 40);
            list->AddCircle(V(c), r, White(0.5f), 40, 0.004f * u);
        }
        const ImU32 mark = White(0.85f);
        const float m = r * 0.45f;
        const float t = 0.007f * u;
        switch (button.shape) {
        case Shape::Cross:
            list->AddLine(V(c + glm::vec2(-m, -m)), V(c + glm::vec2(m, m)), mark, t);
            list->AddLine(V(c + glm::vec2(-m, m)), V(c + glm::vec2(m, -m)), mark, t);
            break;
        case Shape::Circle: list->AddCircle(V(c), m, mark, 32, t); break;
        case Shape::Square: list->AddRect(V(c - glm::vec2(m * 0.85f)), V(c + glm::vec2(m * 0.85f)), mark, 0.0f, 0, t); break;
        case Shape::Triangle:
            list->AddTriangle(V(c + glm::vec2(0.0f, -m)), V(c + glm::vec2(m * 0.95f, m * 0.7f)), V(c + glm::vec2(-m * 0.95f, m * 0.7f)), mark, t);
            break;
        case Shape::Label: label(c, std::min(r * 0.62f, 0.045f * u) * (button.pill ? 1.1f : 1.0f), button.label, mark); break;
        }
    }
}

}
