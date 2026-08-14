package cn.li.presentation.core;

public interface PresentationViewModel extends AutoCloseable {
    BindingTable bindings();
    ActionResult dispatch(ActionId action, ActionPayload payload);

    @Override default void close() {}
}
