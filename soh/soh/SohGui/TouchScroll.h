#ifndef TOUCH_SCROLL_H
#define TOUCH_SCROLL_H

// Drag-to-scroll with momentum for ImGui windows on touch screens, so lists can be scrolled
// by swiping anywhere instead of grabbing the scrollbar.
namespace TouchScroll {

// Call once per ImGui frame, after NewFrame.
void Update();

// True while a finger is down and it isn't yet clear whether it's a tap, a sideways drag or a
// scroll. Widgets that would change a value on touch-down should hold off until then.
bool IsGestureUndecided();

} // namespace TouchScroll

#endif // TOUCH_SCROLL_H
