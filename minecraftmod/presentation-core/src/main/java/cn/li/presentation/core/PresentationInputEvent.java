package cn.li.presentation.core;

public sealed interface PresentationInputEvent
        permits PresentationInputEvent.Pointer, PresentationInputEvent.Key,
                PresentationInputEvent.CharacterInput, PresentationInputEvent.Scroll {
    record Pointer(Type type, float x, float y, int button) implements PresentationInputEvent {
        public enum Type { MOVE, DOWN, UP }
    }
    record Key(int keyCode, boolean pressed, boolean shift, boolean control, boolean alt) implements PresentationInputEvent {}
    record CharacterInput(String text, boolean composing) implements PresentationInputEvent {
        public CharacterInput { text = text == null ? "" : text; }
    }
    record Scroll(float x, float y) implements PresentationInputEvent {}
}
