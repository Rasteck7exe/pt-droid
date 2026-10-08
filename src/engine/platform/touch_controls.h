#pragma once

#include <glm/glm.hpp>

#include <array>
#include <cstdint>
#include <vector>

union SDL_Event;
struct ImDrawList;

namespace pt {

// On-screen controls for touch screens (docs/android.md), shown while no physical gamepad is connected. They behave as
// one more PS4 pad: a floating movement stick on the left half, a floating look stick on the right half, the four face
// buttons, a D-pad, L1/R1, ZOOM (R3), L3, OPTIONS, the touch pad button and a button for the PC settings. InputDevice
// merges their state with the real pads (src/engine/platform/input.cpp) and main draws them over the frame with ImGui.
class TouchControls {
public:
    struct State {
        uint32_t raw = 0;       // PadRaw bits held
        glm::vec2 left{0.0f};   // movement stick, y up (forward)
        glm::vec2 right{0.0f};  // look stick, y down, as SDL reports a pad's right stick
        bool active = false;    // a finger is on a control
        bool pressed = false;   // a button went down since the last Take()
        bool settings = false;  // the PC settings button was tapped since the last Take()
    };

    void SetEnabled(bool enabled);
    bool Enabled() const { return enabled_; }
    // the size the controls are laid out and drawn in (ImGui's display size); finger positions are scaled to it
    void SetViewSize(float width, float height);
    void ProcessEvent(const SDL_Event& event);
    // the held state, with the edges since the last call
    State Take();
    void Draw(ImDrawList* list) const;

private:
    enum class Control : uint8_t { None, LeftStick, RightStick, DPad, Button };
    enum class Shape : uint8_t { Cross, Circle, Square, Triangle, Label };

    struct Button {
        uint32_t raw = 0;
        bool settings = false;
        Shape shape = Shape::Label;
        const char* label = "";
        glm::vec2 center{0.0f};
        float radius = 0.0f;
        bool pill = false;
    };

    struct Finger {
        uint64_t id = 0;
        Control control = Control::None;
        int button = -1;
        glm::vec2 origin{0.0f};
        glm::vec2 now{0.0f};
    };

    struct Layout {
        std::vector<Button> buttons;
        glm::vec2 dpad{0.0f};
        float dpad_size = 0.0f;
        float stick_radius = 0.0f;
        glm::vec2 left_home{0.0f};
        glm::vec2 right_home{0.0f};
    };

    Layout MakeLayout() const;
    void Release(uint64_t id);
    uint32_t DPadBits(glm::vec2 offset) const;
    glm::vec2 StickValue(const Finger& finger) const;

    bool enabled_ = false;
    glm::vec2 size_{1920.0f, 1080.0f};
    Layout layout_;
    std::vector<Finger> fingers_;
    bool pressed_edge_ = false;
    bool settings_edge_ = false;
};

}
